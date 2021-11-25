package com.fasterxml.jackson.core.io;

import java.io.IOException;

/**
 * Eisel-Lemire ParseFloat Algorithm for Java (https://arxiv.org/abs/2101.11408)
 *
 * This implement follows this link:
 * https://nigeltao.github.io/blog/2020/eisel-lemire.html
 */
public final class EiselLemire {
    // powers of 10 which are exactly representable with IEEE754 double precision
    final static double[] EXACT_POW_10 = { 1e0d, 1e1d, 1e2d, 1e3d, 1e4d, 1e5d, 1e6d, 1e7d, 1e8d, 1e9d, 1e10d, 1e11d,
            1e12d, 1e13d, 1e14d, 1e15d, 1e16d, 1e17d, 1e18d, 1e19d, 1e20d, 1e21d, 1e22d, };

    // 52bit mask
    final static long MANTISSA_MASK = 0xFFFFFFFFFFFFFL;

    // Base-10 integer with mantissa * (10 ** exp) format.
    private static class NumExp10 {
        public long mantissa;
        public int exp;
        public boolean neg;
        public boolean trunc;

        public NumExp10(long mantissa, int exp, boolean neg, boolean trunc) {
            this.mantissa = mantissa;
            this.exp = exp;
            this.neg = neg;
            this.trunc = trunc;
        }
    }

    // unsigned 128-bit integer
    private static class U128 {
        public long hi;
        public long lo;

        public U128(long hi, long lo) {
            this.hi = hi;
            this.lo = lo;
        }
    }

    /**
     * Parse JSON number.
     *
     * CAVEAT: This is NOT for a drop-in replacement of Double.parseDouble. We only
     * consider JSON-compat float format, which is defined in RFC7159
     * https://datatracker.ietf.org/doc/html/rfc7159 that means, NaN, Infinity,
     * hexadecimal, leading-zero, space, unary-plus throws NumberFormatException.
     *
     * @param str RFC7159 JSON Number-formatted string.
     * @return parsed double value
     * @throws NumberFormatException
     */
    public static double parseDouble(String str) throws NumberFormatException {
        NumExp10 exp10 = parseToNumExp10(str);

        // fallback if str is unparsable
        if (exp10 == null) {
            return Double.parseDouble(str);
        }

        long mantissa = exp10.mantissa;
        int exp = exp10.exp;
        boolean neg = exp10.neg;
        boolean trunc = exp10.trunc;

        // shortcut for small value (-22 <= exp <= 22)
        if (!trunc && (mantissa >>> 53) == 0) {
            double value = (double) mantissa;
            if (neg) {
                value = -value;
            }

            if (0 <= exp && exp <= 15 + 22) {
                if (exp > 22) {
                    value *= EXACT_POW_10[exp - 22];

                    // exact long is less than 10**15.
                    // then, if the value is still exact even after 10**(exp-22) is multiplied,
                    // we can continue the shortcut path.
                    if (!(value > 1e15d || value < -1e15d)) {
                        return value * EXACT_POW_10[22];
                    }
                } else {
                    return value * EXACT_POW_10[exp];
                }
            } else if (-22 <= exp && exp < 0) {
                return value / EXACT_POW_10[-exp];
            }
        }

        if (mantissa == 0 || exp < -342) {
            return neg ? -0.0 : 0.0;
        }

        if (exp > 308) {
            return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }

        // run Eisel-Lemire algorithm
        double value = eiselLemire64(mantissa, exp, neg);

        if (trunc && !Double.isNaN(value)) {
            double truncValue = eiselLemire64(mantissa + 1, exp, neg);

            // if the string is truncated, compare with mantissa + 1
            // if two numbers are equal, return that number.
            if (value == truncValue) {
                return value;
            } else {
                value = Double.NaN;
            }
        }

        // fallback
        if (Double.isNaN(value)) {
            return Double.parseDouble(str);
        }

        return value;
    }

    /**
     * Main function of Eisel-Lemire algorithm.
     *
     * We followed each step in
     * https://nigeltao.github.io/blog/2020/eisel-lemire.html Subnormal value is not
     * regarded from this implementation.
     *
     * @param mantissa u64 mantissa. Greater or equal than 2^63. (i.e. MSB = 1)
     * @param exp      i32 exponent of 10. (-342 <= exp <= 308)
     * @param neg      negativity of result.
     * @return parsed double value. returns NaN if it should call fallback.
     */
    private static double eiselLemire64(long mantissa, int exp, boolean neg) {
        if (mantissa == 0) {
            // regards negative zero
            return neg ? -0.0 : 0.0;
        }

        // 0. Exp10 Range
        // Use Wuffs [-307, 288] range since we ignore subnormal value.
        if (-307 > exp || exp > 288) {
            return Double.NaN;
        }

        // 1. Normalization
        int leadingZeros = Long.numberOfLeadingZeros(mantissa);
        mantissa = mantissa << leadingZeros;

        // We should calculate m from the equation 2^m <= 10^exp < 2^(m+1)
        // This can be transformed to m <= exp * log2(10) = exp * (1 + log(5))
        // Then, m = floor(exp * log2(5)) + exp
        // This can be effectively calculated with a magic number 217706 ~= 2^16 * (1 +
        // log2(5))
        //
        // Also, 1024 is a bias of exponent part of IEEE754 double precision.
        // We use mantissa within a range [2^63, 2^64-1], so that (63 - leadingZeros)
        // should be added.
        long expBiased = ((217706 * exp) >> 16) + (1024 + 63) - leadingZeros;

        // 2. Multiplication
        U128 mult = mulU64(mantissa, POWER_OF_TEN[exp + 307][1]);

        // 3. Wider approx.
        if ((mult.hi & 0x1FF) == 0x1FF && Long.compareUnsigned(mult.lo + mantissa, mantissa) < 0) {
            U128 wide = mulU64(mantissa, POWER_OF_TEN[exp + 307][0]);
            long newLo = mult.lo + wide.hi;

            // overflow
            if (Long.compareUnsigned(newLo, mult.lo) < 0) {
                mult.hi = mult.hi + 1;
            }

            // check the lower bits of lower bound is 0x1FFFFFFFF....
            // which leads to ambiguity on round to even.
            // the below checks this multiplication is not provably round to even.
            // then fallback.
            if ((mult.hi & 0x1FF) == 0x1FF && (newLo + 1) == 0
                    && Long.compareUnsigned(wide.lo + mantissa, mantissa) < 0) {
                return Double.NaN;
            }

            mult.lo = newLo;
        }

        // 4. Shift to 54 Bits
        // IEEE754 Significand requires 53 (+ implicit leading 1) bits.
        // The multiplication result is in [2^126, 2^128 - 1].
        // To properly make 54 bit which msb is 1,
        // we should check MSB of the result in [2^126, 2^127], which its MSB is 0.
        long msb = mult.hi >>> 63;
        long f64Man = mult.hi >>> (msb + 9);
        expBiased -= (1 ^ msb);

        // 5. Half-way Ambiguity
        // checks the lower bits of lower bound is 0x1000000000.....
        // which also leads to ambiguity on round to even.
        if (mult.lo == 0 && (mult.hi & 0x1FF) == 0 && (f64Man & 0x3) == 1) {
            return Double.NaN;
        }

        // 5. From 54 to 53 bit
        f64Man = (f64Man + (f64Man & 0x1)) >>> 1;

        // overflow check
        if (f64Man >>> 53 > 0) {
            f64Man = f64Man >>> 1;
            expBiased += 1;
        }

        // check expBiased is in subnormal or infinite range
        if (expBiased < 0 || expBiased >= 0x7FFL) {
            return Double.NaN;
        }

        long f64bits = (f64Man & MANTISSA_MASK) | (expBiased << 52);
        if (neg) {
            f64bits = f64bits | 0x8000000000000000L;
        }

        return Double.longBitsToDouble(f64bits);
    }

    /**
     * Parse number and return integer mantissa, exponent, sign. If a length of
     * significand is greater than 19, set truncated to true.
     *
     * @param str input value
     * @return parsed Exp10 format. returns null if the sequence is malformed
     */
      // calculate mantissa after each character digit
    private static long getMantissaPerDigit(long mantissa, char digit) {
        switch (digit) {
        case '0':
            break;
        case '1':
            mantissa += 1;
            break;
        case '2':
            mantissa += 2l;
                break;
        case '3':
            mantissa += 3l;
            break;
        case '4':
            mantissa += 4l;
            break;
        case '5':
            mantissa += 5l;
            break;
        case '6':
            mantissa += 6l;
            break;
        case '7':
            mantissa += 7l;
            break;
        case '8':
            mantissa += 8l;
            break;
        case '9':
            mantissa += 9l;
            break;
        default:
            // if the string doesn't match the form
            mantissa = -1l;
            break;
        }
        return mantissa;
    }
    // calculate exponent after each digit
    private static int getExpoPerDigit(int expo, int sign, char digit) {
        switch (digit) {
        case '0':
            break;
        case '1':
            expo += sign;
            break;
        case '2':
            expo += 2 * sign;
            break;
        case '3':
            expo += 3 * sign;
            break;
        case '4':
            expo += 4 * sign;
            break;
        case '5':
            expo += 5 * sign;
            break;
        case '6':
            expo += 6 * sign;
            break;
        case '7':
            expo += 7 * sign;
            break;
        case '8':
            expo += 8 * sign;
            break;
        case '9':
            expo += 9 * sign;
            break;
        default:
            // if the string doesn't match the form
            expo = Integer.MIN_VALUE;
        }
        return expo;
    }
    
    private static int calculateExpo(int expo, int sign, String input, 
            int pos, long mantissa , int decimalCount) {
        // if the mantissa part is 0 
        if (mantissa == 0l) {
            // to make expo - decimalCount == 0
            return -1 * decimalCount;
        }
        // if the expo part is negative   
        if (input.charAt(pos) == '-') {
            sign=-1;
            pos++;
        }
        // the highest digit after exponent symbol
        switch (input.charAt(pos)) {
        case '0':
            expo = 0;
            break;
        case '1':
            expo = sign;
            break;
        case '2':
            expo = 2 * sign;
            break;
        case '3':
            expo = 3 * sign;
            break;
        case '4':
            expo = 4 * sign;
            break;
        case '5':
            expo = 5 * sign;
            break;
        case '6':
            expo = 6 * sign;
            break;
        case '7':
            expo = 7 * sign;
            break;
        case '8':
            expo = 8 * sign;
            break;
        case '9':
            expo = 9 * sign;
            break;
        default:
            // if the string doesn't match the form
            expo = Integer.MIN_VALUE;
        }
        pos++;
        // calculate exponent until the end of string
        while (pos<input.length()) {
            int tenByExpo = 10 * expo;
            expo = getExpoPerDigit(tenByExpo,sign,input.charAt(pos));
            if (expo == Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            pos++;
        } 
        return expo;
    }
    public static NumExp10 parseToNumExp10(String str) {
        long mantissa = 0l;
        int decimalCount = 0; // count digits after decimal point
        int pos = 0;
        boolean neg = false;
        int expo = 0;
        int sign = 1;
        int digits = 0; // digit count for mantissa
        boolean isTruncated=false;
        boolean isStartingByDecimaPoint = false;
        // if the string starts with '-' then the value is negative
        if (str.charAt(pos)=='-') {
            neg=true;
            pos++;
        }
        // the first digit of mantissa
        switch (str.charAt(pos)) {
        case '0':
            mantissa = 0l;
            digits++;
            break;
        case '1':
            mantissa = 1l;
            digits++;
            break;
        case '2':
            mantissa = 2l;
            digits++;
            break;
        case '3':
            mantissa = 3l;
            digits++;
            break;
        case '4':
            mantissa = 4l;
            digits++;
            break;
        case '5':
            mantissa = 5l;
            digits++;
            break;
        case '6':
            mantissa = 6l;
            digits++;
            break;
        case '7':
            mantissa = 7l;
            digits++;
            break;
        case '8':
            mantissa = 8l;
            digits++;
            break;
        case '9':
            mantissa = 9l;
            digits++;
            break;
        case '.':
            // if the mantissa part of string starts with '.'
            mantissa = 0l;
            isStartingByDecimaPoint = true;
            break;
        default:
            return null;
        }
        pos++;
        calcFloatPoint:while (pos < str.length()) {
            // if the char equals 'e' or 'E' calculate the exponent
            if ((str.charAt(pos) == 'e') || (str.charAt(pos) == 'E')) {
                pos++;
                expo = calculateExpo(expo, sign, str, pos, mantissa, decimalCount);
                // if the string doens't match the form
                if (expo == Integer.MIN_VALUE) {
                    return null;
                }
                break;
            } else if (str.charAt(pos) == '.'){ // when it meets decimal point
                pos++;
                while (pos < str.length()) {
                    // if the char equals 'e' or 'E' calculate the exponent
                    if ((str.charAt(pos) == 'e') || (str.charAt(pos) == 'E')) {
                        pos++;
                        expo = calculateExpo(expo, sign, str, pos, mantissa, decimalCount);
                        if (expo == Integer.MIN_VALUE) {
                            return null;
                        }
                        break calcFloatPoint;
                    }
                    long mantissaTmp = mantissa;
                    int decimalPointTmp = decimalCount;
                    // look at each digits util the character is not '0'
                    while (str.charAt(pos) == '0') {
                        decimalPointTmp++;
                        long tenByTmp = mantissaTmp * 10l;
                        mantissaTmp = getMantissaPerDigit(tenByTmp,str.charAt(pos));
                        digits++;
                        // if the mantissa has over 19 digits
                        if ((digits >= 19) && (mantissaTmp != 0)) {
                            isTruncated = true;
                        }
                        // it the string doesn't match the form
                        if (mantissaTmp == -1l) {
                            return null;
                        }
                        pos++;
                        // if the string ends
                        if (pos == str.length()) {
                            break calcFloatPoint;
                        }
                    }
                    // if the char equals 'e' or 'E' calculate the exponent
                    if ((str.charAt(pos) == 'e') || (str.charAt(pos) == 'E')) {
                        pos++;
                        expo = calculateExpo(expo, sign, str, pos, mantissa, decimalCount);
                        // if the string doesn't match the form
                        if (expo == Integer.MIN_VALUE) {
                            return null;
                        }
                        break calcFloatPoint;
                    }
                    mantissa = mantissaTmp;
                    decimalCount = decimalPointTmp;
                    decimalCount++;
                    long tenByMantissa = 10l * mantissa;
                    mantissa = getMantissaPerDigit(tenByMantissa, str.charAt(pos));
                    digits++;
                    // if the mantissa has over 19 digits
                    if ((digits >= 19) && (mantissa != 0)) {
                        isTruncated = true;
                    }
                    // if the string doesn't match the form
                    if (mantissa == -1l) {
                        return null;
                    }

                    pos++;
                    
                }
            } else {
                // if the mantissa part started with '.'
                if (isStartingByDecimaPoint) {
                    decimalCount++;
                }
                long tenByMantissa = 10l * mantissa;
                mantissa = getMantissaPerDigit(tenByMantissa, str.charAt(pos));
                digits++;
                // if the mantissa has over 19 digits
                if ((digits >= 19) && (mantissa != 0)) {
                    isTruncated = true;
                }
                // if the string doesn't match the form
                if (mantissa == -1l) {
                    return null;
                }

                pos++;
            }
        } 
        NumExp10 result = new NumExp10(mantissa, (expo - decimalCount), neg, isTruncated);
        return result;
    }
    /**
     * Faithfully multiply two unsigned long value.
     *
     * @param x u64 long integer
     * @param y u64 long integer
     * @return u128 multiplied long integer
     */
    private static U128 mulU64(long x, long y) {
        long xH = x >>> 32;
        long xL = x & 0xFFFFFFFFL;
        long yH = y >>> 32;
        long yL = y & 0xFFFFFFFFL;

        long mHH = xH * yH;
        long mHL = xH * yL;
        long mLH = xL * yH;
        long mLL = xL * yL;

        long mid = mHL + (mLL >>> 32) + (mLH & 0xFFFFFFFFL);
        long hi = mHH + (mid >>> 32) + (mLH >>> 32);
        long lo = (mid << 32) | (mLL & 0xFFFFFFFFL);

        return new U128(hi, lo);
    }

    // Table of powers of ten. (1e-342 to 1e308), copied from
    // Each column contains (Lower, Higher) 128-bit representation of power of ten.
    //
    // e.g.) 1e43 = (0xE596B7B0_C643C719_6D9CCD05_D0000000 * (2 ** 15))
    // then POWER_OF_TEN[43 + 342] = { 0x6D9CCD05D0000000L, 0xE596B7B0C643C719L }
    // copied from:
    // https://github.com/golang/go/blob/2ebe77a2fda1ee9ff6fd9a3e08933ad1ebaea039/src/strconv/eisel_lemire.go
    final static long[][] POWER_OF_TEN = { { 0xA5D3B6D479F8E056L, 0x8FD0C16206306BABL }, // 1e-307
            { 0x8F48A4899877186CL, 0xB3C4F1BA87BC8696L }, // 1e-306
            { 0x331ACDABFE94DE87L, 0xE0B62E2929ABA83CL }, // 1e-305
            { 0x9FF0C08B7F1D0B14L, 0x8C71DCD9BA0B4925L }, // 1e-304
            { 0x07ECF0AE5EE44DD9L, 0xAF8E5410288E1B6FL }, // 1e-303
            { 0xC9E82CD9F69D6150L, 0xDB71E91432B1A24AL }, // 1e-302
            { 0xBE311C083A225CD2L, 0x892731AC9FAF056EL }, // 1e-301
            { 0x6DBD630A48AAF406L, 0xAB70FE17C79AC6CAL }, // 1e-300
            { 0x092CBBCCDAD5B108L, 0xD64D3D9DB981787DL }, // 1e-299
            { 0x25BBF56008C58EA5L, 0x85F0468293F0EB4EL }, // 1e-298
            { 0xAF2AF2B80AF6F24EL, 0xA76C582338ED2621L }, // 1e-297
            { 0x1AF5AF660DB4AEE1L, 0xD1476E2C07286FAAL }, // 1e-296
            { 0x50D98D9FC890ED4DL, 0x82CCA4DB847945CAL }, // 1e-295
            { 0xE50FF107BAB528A0L, 0xA37FCE126597973CL }, // 1e-294
            { 0x1E53ED49A96272C8L, 0xCC5FC196FEFD7D0CL }, // 1e-293
            { 0x25E8E89C13BB0F7AL, 0xFF77B1FCBEBCDC4FL }, // 1e-292
            { 0x77B191618C54E9ACL, 0x9FAACF3DF73609B1L }, // 1e-291
            { 0xD59DF5B9EF6A2417L, 0xC795830D75038C1DL }, // 1e-290
            { 0x4B0573286B44AD1DL, 0xF97AE3D0D2446F25L }, // 1e-289
            { 0x4EE367F9430AEC32L, 0x9BECCE62836AC577L }, // 1e-288
            { 0x229C41F793CDA73FL, 0xC2E801FB244576D5L }, // 1e-287
            { 0x6B43527578C1110FL, 0xF3A20279ED56D48AL }, // 1e-286
            { 0x830A13896B78AAA9L, 0x9845418C345644D6L }, // 1e-285
            { 0x23CC986BC656D553L, 0xBE5691EF416BD60CL }, // 1e-284
            { 0x2CBFBE86B7EC8AA8L, 0xEDEC366B11C6CB8FL }, // 1e-283
            { 0x7BF7D71432F3D6A9L, 0x94B3A202EB1C3F39L }, // 1e-282
            { 0xDAF5CCD93FB0CC53L, 0xB9E08A83A5E34F07L }, // 1e-281
            { 0xD1B3400F8F9CFF68L, 0xE858AD248F5C22C9L }, // 1e-280
            { 0x23100809B9C21FA1L, 0x91376C36D99995BEL }, // 1e-279
            { 0xABD40A0C2832A78AL, 0xB58547448FFFFB2DL }, // 1e-278
            { 0x16C90C8F323F516CL, 0xE2E69915B3FFF9F9L }, // 1e-277
            { 0xAE3DA7D97F6792E3L, 0x8DD01FAD907FFC3BL }, // 1e-276
            { 0x99CD11CFDF41779CL, 0xB1442798F49FFB4AL }, // 1e-275
            { 0x40405643D711D583L, 0xDD95317F31C7FA1DL }, // 1e-274
            { 0x482835EA666B2572L, 0x8A7D3EEF7F1CFC52L }, // 1e-273
            { 0xDA3243650005EECFL, 0xAD1C8EAB5EE43B66L }, // 1e-272
            { 0x90BED43E40076A82L, 0xD863B256369D4A40L }, // 1e-271
            { 0x5A7744A6E804A291L, 0x873E4F75E2224E68L }, // 1e-270
            { 0x711515D0A205CB36L, 0xA90DE3535AAAE202L }, // 1e-269
            { 0x0D5A5B44CA873E03L, 0xD3515C2831559A83L }, // 1e-268
            { 0xE858790AFE9486C2L, 0x8412D9991ED58091L }, // 1e-267
            { 0x626E974DBE39A872L, 0xA5178FFF668AE0B6L }, // 1e-266
            { 0xFB0A3D212DC8128FL, 0xCE5D73FF402D98E3L }, // 1e-265
            { 0x7CE66634BC9D0B99L, 0x80FA687F881C7F8EL }, // 1e-264
            { 0x1C1FFFC1EBC44E80L, 0xA139029F6A239F72L }, // 1e-263
            { 0xA327FFB266B56220L, 0xC987434744AC874EL }, // 1e-262
            { 0x4BF1FF9F0062BAA8L, 0xFBE9141915D7A922L }, // 1e-261
            { 0x6F773FC3603DB4A9L, 0x9D71AC8FADA6C9B5L }, // 1e-260
            { 0xCB550FB4384D21D3L, 0xC4CE17B399107C22L }, // 1e-259
            { 0x7E2A53A146606A48L, 0xF6019DA07F549B2BL }, // 1e-258
            { 0x2EDA7444CBFC426DL, 0x99C102844F94E0FBL }, // 1e-257
            { 0xFA911155FEFB5308L, 0xC0314325637A1939L }, // 1e-256
            { 0x793555AB7EBA27CAL, 0xF03D93EEBC589F88L }, // 1e-255
            { 0x4BC1558B2F3458DEL, 0x96267C7535B763B5L }, // 1e-254
            { 0x9EB1AAEDFB016F16L, 0xBBB01B9283253CA2L }, // 1e-253
            { 0x465E15A979C1CADCL, 0xEA9C227723EE8BCBL }, // 1e-252
            { 0x0BFACD89EC191EC9L, 0x92A1958A7675175FL }, // 1e-251
            { 0xCEF980EC671F667BL, 0xB749FAED14125D36L }, // 1e-250
            { 0x82B7E12780E7401AL, 0xE51C79A85916F484L }, // 1e-249
            { 0xD1B2ECB8B0908810L, 0x8F31CC0937AE58D2L }, // 1e-248
            { 0x861FA7E6DCB4AA15L, 0xB2FE3F0B8599EF07L }, // 1e-247
            { 0x67A791E093E1D49AL, 0xDFBDCECE67006AC9L }, // 1e-246
            { 0xE0C8BB2C5C6D24E0L, 0x8BD6A141006042BDL }, // 1e-245
            { 0x58FAE9F773886E18L, 0xAECC49914078536DL }, // 1e-244
            { 0xAF39A475506A899EL, 0xDA7F5BF590966848L }, // 1e-243
            { 0x6D8406C952429603L, 0x888F99797A5E012DL }, // 1e-242
            { 0xC8E5087BA6D33B83L, 0xAAB37FD7D8F58178L }, // 1e-241
            { 0xFB1E4A9A90880A64L, 0xD5605FCDCF32E1D6L }, // 1e-240
            { 0x5CF2EEA09A55067FL, 0x855C3BE0A17FCD26L }, // 1e-239
            { 0xF42FAA48C0EA481EL, 0xA6B34AD8C9DFC06FL }, // 1e-238
            { 0xF13B94DAF124DA26L, 0xD0601D8EFC57B08BL }, // 1e-237
            { 0x76C53D08D6B70858L, 0x823C12795DB6CE57L }, // 1e-236
            { 0x54768C4B0C64CA6EL, 0xA2CB1717B52481EDL }, // 1e-235
            { 0xA9942F5DCF7DFD09L, 0xCB7DDCDDA26DA268L }, // 1e-234
            { 0xD3F93B35435D7C4CL, 0xFE5D54150B090B02L }, // 1e-233
            { 0xC47BC5014A1A6DAFL, 0x9EFA548D26E5A6E1L }, // 1e-232
            { 0x359AB6419CA1091BL, 0xC6B8E9B0709F109AL }, // 1e-231
            { 0xC30163D203C94B62L, 0xF867241C8CC6D4C0L }, // 1e-230
            { 0x79E0DE63425DCF1DL, 0x9B407691D7FC44F8L }, // 1e-229
            { 0x985915FC12F542E4L, 0xC21094364DFB5636L }, // 1e-228
            { 0x3E6F5B7B17B2939DL, 0xF294B943E17A2BC4L }, // 1e-227
            { 0xA705992CEECF9C42L, 0x979CF3CA6CEC5B5AL }, // 1e-226
            { 0x50C6FF782A838353L, 0xBD8430BD08277231L }, // 1e-225
            { 0xA4F8BF5635246428L, 0xECE53CEC4A314EBDL }, // 1e-224
            { 0x871B7795E136BE99L, 0x940F4613AE5ED136L }, // 1e-223
            { 0x28E2557B59846E3FL, 0xB913179899F68584L }, // 1e-222
            { 0x331AEADA2FE589CFL, 0xE757DD7EC07426E5L }, // 1e-221
            { 0x3FF0D2C85DEF7621L, 0x9096EA6F3848984FL }, // 1e-220
            { 0x0FED077A756B53A9L, 0xB4BCA50B065ABE63L }, // 1e-219
            { 0xD3E8495912C62894L, 0xE1EBCE4DC7F16DFBL }, // 1e-218
            { 0x64712DD7ABBBD95CL, 0x8D3360F09CF6E4BDL }, // 1e-217
            { 0xBD8D794D96AACFB3L, 0xB080392CC4349DECL }, // 1e-216
            { 0xECF0D7A0FC5583A0L, 0xDCA04777F541C567L }, // 1e-215
            { 0xF41686C49DB57244L, 0x89E42CAAF9491B60L }, // 1e-214
            { 0x311C2875C522CED5L, 0xAC5D37D5B79B6239L }, // 1e-213
            { 0x7D633293366B828BL, 0xD77485CB25823AC7L }, // 1e-212
            { 0xAE5DFF9C02033197L, 0x86A8D39EF77164BCL }, // 1e-211
            { 0xD9F57F830283FDFCL, 0xA8530886B54DBDEBL }, // 1e-210
            { 0xD072DF63C324FD7BL, 0xD267CAA862A12D66L }, // 1e-209
            { 0x4247CB9E59F71E6DL, 0x8380DEA93DA4BC60L }, // 1e-208
            { 0x52D9BE85F074E608L, 0xA46116538D0DEB78L }, // 1e-207
            { 0x67902E276C921F8BL, 0xCD795BE870516656L }, // 1e-206
            { 0x00BA1CD8A3DB53B6L, 0x806BD9714632DFF6L }, // 1e-205
            { 0x80E8A40ECCD228A4L, 0xA086CFCD97BF97F3L }, // 1e-204
            { 0x6122CD128006B2CDL, 0xC8A883C0FDAF7DF0L }, // 1e-203
            { 0x796B805720085F81L, 0xFAD2A4B13D1B5D6CL }, // 1e-202
            { 0xCBE3303674053BB0L, 0x9CC3A6EEC6311A63L }, // 1e-201
            { 0xBEDBFC4411068A9CL, 0xC3F490AA77BD60FCL }, // 1e-200
            { 0xEE92FB5515482D44L, 0xF4F1B4D515ACB93BL }, // 1e-199
            { 0x751BDD152D4D1C4AL, 0x991711052D8BF3C5L }, // 1e-198
            { 0xD262D45A78A0635DL, 0xBF5CD54678EEF0B6L }, // 1e-197
            { 0x86FB897116C87C34L, 0xEF340A98172AACE4L }, // 1e-196
            { 0xD45D35E6AE3D4DA0L, 0x9580869F0E7AAC0EL }, // 1e-195
            { 0x8974836059CCA109L, 0xBAE0A846D2195712L }, // 1e-194
            { 0x2BD1A438703FC94BL, 0xE998D258869FACD7L }, // 1e-193
            { 0x7B6306A34627DDCFL, 0x91FF83775423CC06L }, // 1e-192
            { 0x1A3BC84C17B1D542L, 0xB67F6455292CBF08L }, // 1e-191
            { 0x20CABA5F1D9E4A93L, 0xE41F3D6A7377EECAL }, // 1e-190
            { 0x547EB47B7282EE9CL, 0x8E938662882AF53EL }, // 1e-189
            { 0xE99E619A4F23AA43L, 0xB23867FB2A35B28DL }, // 1e-188
            { 0x6405FA00E2EC94D4L, 0xDEC681F9F4C31F31L }, // 1e-187
            { 0xDE83BC408DD3DD04L, 0x8B3C113C38F9F37EL }, // 1e-186
            { 0x9624AB50B148D445L, 0xAE0B158B4738705EL }, // 1e-185
            { 0x3BADD624DD9B0957L, 0xD98DDAEE19068C76L }, // 1e-184
            { 0xE54CA5D70A80E5D6L, 0x87F8A8D4CFA417C9L }, // 1e-183
            { 0x5E9FCF4CCD211F4CL, 0xA9F6D30A038D1DBCL }, // 1e-182
            { 0x7647C3200069671FL, 0xD47487CC8470652BL }, // 1e-181
            { 0x29ECD9F40041E073L, 0x84C8D4DFD2C63F3BL }, // 1e-180
            { 0xF468107100525890L, 0xA5FB0A17C777CF09L }, // 1e-179
            { 0x7182148D4066EEB4L, 0xCF79CC9DB955C2CCL }, // 1e-178
            { 0xC6F14CD848405530L, 0x81AC1FE293D599BFL }, // 1e-177
            { 0xB8ADA00E5A506A7CL, 0xA21727DB38CB002FL }, // 1e-176
            { 0xA6D90811F0E4851CL, 0xCA9CF1D206FDC03BL }, // 1e-175
            { 0x908F4A166D1DA663L, 0xFD442E4688BD304AL }, // 1e-174
            { 0x9A598E4E043287FEL, 0x9E4A9CEC15763E2EL }, // 1e-173
            { 0x40EFF1E1853F29FDL, 0xC5DD44271AD3CDBAL }, // 1e-172
            { 0xD12BEE59E68EF47CL, 0xF7549530E188C128L }, // 1e-171
            { 0x82BB74F8301958CEL, 0x9A94DD3E8CF578B9L }, // 1e-170
            { 0xE36A52363C1FAF01L, 0xC13A148E3032D6E7L }, // 1e-169
            { 0xDC44E6C3CB279AC1L, 0xF18899B1BC3F8CA1L }, // 1e-168
            { 0x29AB103A5EF8C0B9L, 0x96F5600F15A7B7E5L }, // 1e-167
            { 0x7415D448F6B6F0E7L, 0xBCB2B812DB11A5DEL }, // 1e-166
            { 0x111B495B3464AD21L, 0xEBDF661791D60F56L }, // 1e-165
            { 0xCAB10DD900BEEC34L, 0x936B9FCEBB25C995L }, // 1e-164
            { 0x3D5D514F40EEA742L, 0xB84687C269EF3BFBL }, // 1e-163
            { 0x0CB4A5A3112A5112L, 0xE65829B3046B0AFAL }, // 1e-162
            { 0x47F0E785EABA72ABL, 0x8FF71A0FE2C2E6DCL }, // 1e-161
            { 0x59ED216765690F56L, 0xB3F4E093DB73A093L }, // 1e-160
            { 0x306869C13EC3532CL, 0xE0F218B8D25088B8L }, // 1e-159
            { 0x1E414218C73A13FBL, 0x8C974F7383725573L }, // 1e-158
            { 0xE5D1929EF90898FAL, 0xAFBD2350644EEACFL }, // 1e-157
            { 0xDF45F746B74ABF39L, 0xDBAC6C247D62A583L }, // 1e-156
            { 0x6B8BBA8C328EB783L, 0x894BC396CE5DA772L }, // 1e-155
            { 0x066EA92F3F326564L, 0xAB9EB47C81F5114FL }, // 1e-154
            { 0xC80A537B0EFEFEBDL, 0xD686619BA27255A2L }, // 1e-153
            { 0xBD06742CE95F5F36L, 0x8613FD0145877585L }, // 1e-152
            { 0x2C48113823B73704L, 0xA798FC4196E952E7L }, // 1e-151
            { 0xF75A15862CA504C5L, 0xD17F3B51FCA3A7A0L }, // 1e-150
            { 0x9A984D73DBE722FBL, 0x82EF85133DE648C4L }, // 1e-149
            { 0xC13E60D0D2E0EBBAL, 0xA3AB66580D5FDAF5L }, // 1e-148
            { 0x318DF905079926A8L, 0xCC963FEE10B7D1B3L }, // 1e-147
            { 0xFDF17746497F7052L, 0xFFBBCFE994E5C61FL }, // 1e-146
            { 0xFEB6EA8BEDEFA633L, 0x9FD561F1FD0F9BD3L }, // 1e-145
            { 0xFE64A52EE96B8FC0L, 0xC7CABA6E7C5382C8L }, // 1e-144
            { 0x3DFDCE7AA3C673B0L, 0xF9BD690A1B68637BL }, // 1e-143
            { 0x06BEA10CA65C084EL, 0x9C1661A651213E2DL }, // 1e-142
            { 0x486E494FCFF30A62L, 0xC31BFA0FE5698DB8L }, // 1e-141
            { 0x5A89DBA3C3EFCCFAL, 0xF3E2F893DEC3F126L }, // 1e-140
            { 0xF89629465A75E01CL, 0x986DDB5C6B3A76B7L }, // 1e-139
            { 0xF6BBB397F1135823L, 0xBE89523386091465L }, // 1e-138
            { 0x746AA07DED582E2CL, 0xEE2BA6C0678B597FL }, // 1e-137
            { 0xA8C2A44EB4571CDCL, 0x94DB483840B717EFL }, // 1e-136
            { 0x92F34D62616CE413L, 0xBA121A4650E4DDEBL }, // 1e-135
            { 0x77B020BAF9C81D17L, 0xE896A0D7E51E1566L }, // 1e-134
            { 0x0ACE1474DC1D122EL, 0x915E2486EF32CD60L }, // 1e-133
            { 0x0D819992132456BAL, 0xB5B5ADA8AAFF80B8L }, // 1e-132
            { 0x10E1FFF697ED6C69L, 0xE3231912D5BF60E6L }, // 1e-131
            { 0xCA8D3FFA1EF463C1L, 0x8DF5EFABC5979C8FL }, // 1e-130
            { 0xBD308FF8A6B17CB2L, 0xB1736B96B6FD83B3L }, // 1e-129
            { 0xAC7CB3F6D05DDBDEL, 0xDDD0467C64BCE4A0L }, // 1e-128
            { 0x6BCDF07A423AA96BL, 0x8AA22C0DBEF60EE4L }, // 1e-127
            { 0x86C16C98D2C953C6L, 0xAD4AB7112EB3929DL }, // 1e-126
            { 0xE871C7BF077BA8B7L, 0xD89D64D57A607744L }, // 1e-125
            { 0x11471CD764AD4972L, 0x87625F056C7C4A8BL }, // 1e-124
            { 0xD598E40D3DD89BCFL, 0xA93AF6C6C79B5D2DL }, // 1e-123
            { 0x4AFF1D108D4EC2C3L, 0xD389B47879823479L }, // 1e-122
            { 0xCEDF722A585139BAL, 0x843610CB4BF160CBL }, // 1e-121
            { 0xC2974EB4EE658828L, 0xA54394FE1EEDB8FEL }, // 1e-120
            { 0x733D226229FEEA32L, 0xCE947A3DA6A9273EL }, // 1e-119
            { 0x0806357D5A3F525FL, 0x811CCC668829B887L }, // 1e-118
            { 0xCA07C2DCB0CF26F7L, 0xA163FF802A3426A8L }, // 1e-117
            { 0xFC89B393DD02F0B5L, 0xC9BCFF6034C13052L }, // 1e-116
            { 0xBBAC2078D443ACE2L, 0xFC2C3F3841F17C67L }, // 1e-115
            { 0xD54B944B84AA4C0DL, 0x9D9BA7832936EDC0L }, // 1e-114
            { 0x0A9E795E65D4DF11L, 0xC5029163F384A931L }, // 1e-113
            { 0x4D4617B5FF4A16D5L, 0xF64335BCF065D37DL }, // 1e-112
            { 0x504BCED1BF8E4E45L, 0x99EA0196163FA42EL }, // 1e-111
            { 0xE45EC2862F71E1D6L, 0xC06481FB9BCF8D39L }, // 1e-110
            { 0x5D767327BB4E5A4CL, 0xF07DA27A82C37088L }, // 1e-109
            { 0x3A6A07F8D510F86FL, 0x964E858C91BA2655L }, // 1e-108
            { 0x890489F70A55368BL, 0xBBE226EFB628AFEAL }, // 1e-107
            { 0x2B45AC74CCEA842EL, 0xEADAB0ABA3B2DBE5L }, // 1e-106
            { 0x3B0B8BC90012929DL, 0x92C8AE6B464FC96FL }, // 1e-105
            { 0x09CE6EBB40173744L, 0xB77ADA0617E3BBCBL }, // 1e-104
            { 0xCC420A6A101D0515L, 0xE55990879DDCAABDL }, // 1e-103
            { 0x9FA946824A12232DL, 0x8F57FA54C2A9EAB6L }, // 1e-102
            { 0x47939822DC96ABF9L, 0xB32DF8E9F3546564L }, // 1e-101
            { 0x59787E2B93BC56F7L, 0xDFF9772470297EBDL }, // 1e-100
            { 0x57EB4EDB3C55B65AL, 0x8BFBEA76C619EF36L }, // 1e-99
            { 0xEDE622920B6B23F1L, 0xAEFAE51477A06B03L }, // 1e-98
            { 0xE95FAB368E45ECEDL, 0xDAB99E59958885C4L }, // 1e-97
            { 0x11DBCB0218EBB414L, 0x88B402F7FD75539BL }, // 1e-96
            { 0xD652BDC29F26A119L, 0xAAE103B5FCD2A881L }, // 1e-95
            { 0x4BE76D3346F0495FL, 0xD59944A37C0752A2L }, // 1e-94
            { 0x6F70A4400C562DDBL, 0x857FCAE62D8493A5L }, // 1e-93
            { 0xCB4CCD500F6BB952L, 0xA6DFBD9FB8E5B88EL }, // 1e-92
            { 0x7E2000A41346A7A7L, 0xD097AD07A71F26B2L }, // 1e-91
            { 0x8ED400668C0C28C8L, 0x825ECC24C873782FL }, // 1e-90
            { 0x728900802F0F32FAL, 0xA2F67F2DFA90563BL }, // 1e-89
            { 0x4F2B40A03AD2FFB9L, 0xCBB41EF979346BCAL }, // 1e-88
            { 0xE2F610C84987BFA8L, 0xFEA126B7D78186BCL }, // 1e-87
            { 0x0DD9CA7D2DF4D7C9L, 0x9F24B832E6B0F436L }, // 1e-86
            { 0x91503D1C79720DBBL, 0xC6EDE63FA05D3143L }, // 1e-85
            { 0x75A44C6397CE912AL, 0xF8A95FCF88747D94L }, // 1e-84
            { 0xC986AFBE3EE11ABAL, 0x9B69DBE1B548CE7CL }, // 1e-83
            { 0xFBE85BADCE996168L, 0xC24452DA229B021BL }, // 1e-82
            { 0xFAE27299423FB9C3L, 0xF2D56790AB41C2A2L }, // 1e-81
            { 0xDCCD879FC967D41AL, 0x97C560BA6B0919A5L }, // 1e-80
            { 0x5400E987BBC1C920L, 0xBDB6B8E905CB600FL }, // 1e-79
            { 0x290123E9AAB23B68L, 0xED246723473E3813L }, // 1e-78
            { 0xF9A0B6720AAF6521L, 0x9436C0760C86E30BL }, // 1e-77
            { 0xF808E40E8D5B3E69L, 0xB94470938FA89BCEL }, // 1e-76
            { 0xB60B1D1230B20E04L, 0xE7958CB87392C2C2L }, // 1e-75
            { 0xB1C6F22B5E6F48C2L, 0x90BD77F3483BB9B9L }, // 1e-74
            { 0x1E38AEB6360B1AF3L, 0xB4ECD5F01A4AA828L }, // 1e-73
            { 0x25C6DA63C38DE1B0L, 0xE2280B6C20DD5232L }, // 1e-72
            { 0x579C487E5A38AD0EL, 0x8D590723948A535FL }, // 1e-71
            { 0x2D835A9DF0C6D851L, 0xB0AF48EC79ACE837L }, // 1e-70
            { 0xF8E431456CF88E65L, 0xDCDB1B2798182244L }, // 1e-69
            { 0x1B8E9ECB641B58FFL, 0x8A08F0F8BF0F156BL }, // 1e-68
            { 0xE272467E3D222F3FL, 0xAC8B2D36EED2DAC5L }, // 1e-67
            { 0x5B0ED81DCC6ABB0FL, 0xD7ADF884AA879177L }, // 1e-66
            { 0x98E947129FC2B4E9L, 0x86CCBB52EA94BAEAL }, // 1e-65
            { 0x3F2398D747B36224L, 0xA87FEA27A539E9A5L }, // 1e-64
            { 0x8EEC7F0D19A03AADL, 0xD29FE4B18E88640EL }, // 1e-63
            { 0x1953CF68300424ACL, 0x83A3EEEEF9153E89L }, // 1e-62
            { 0x5FA8C3423C052DD7L, 0xA48CEAAAB75A8E2BL }, // 1e-61
            { 0x3792F412CB06794DL, 0xCDB02555653131B6L }, // 1e-60
            { 0xE2BBD88BBEE40BD0L, 0x808E17555F3EBF11L }, // 1e-59
            { 0x5B6ACEAEAE9D0EC4L, 0xA0B19D2AB70E6ED6L }, // 1e-58
            { 0xF245825A5A445275L, 0xC8DE047564D20A8BL }, // 1e-57
            { 0xEED6E2F0F0D56712L, 0xFB158592BE068D2EL }, // 1e-56
            { 0x55464DD69685606BL, 0x9CED737BB6C4183DL }, // 1e-55
            { 0xAA97E14C3C26B886L, 0xC428D05AA4751E4CL }, // 1e-54
            { 0xD53DD99F4B3066A8L, 0xF53304714D9265DFL }, // 1e-53
            { 0xE546A8038EFE4029L, 0x993FE2C6D07B7FABL }, // 1e-52
            { 0xDE98520472BDD033L, 0xBF8FDB78849A5F96L }, // 1e-51
            { 0x963E66858F6D4440L, 0xEF73D256A5C0F77CL }, // 1e-50
            { 0xDDE7001379A44AA8L, 0x95A8637627989AADL }, // 1e-49
            { 0x5560C018580D5D52L, 0xBB127C53B17EC159L }, // 1e-48
            { 0xAAB8F01E6E10B4A6L, 0xE9D71B689DDE71AFL }, // 1e-47
            { 0xCAB3961304CA70E8L, 0x9226712162AB070DL }, // 1e-46
            { 0x3D607B97C5FD0D22L, 0xB6B00D69BB55C8D1L }, // 1e-45
            { 0x8CB89A7DB77C506AL, 0xE45C10C42A2B3B05L }, // 1e-44
            { 0x77F3608E92ADB242L, 0x8EB98A7A9A5B04E3L }, // 1e-43
            { 0x55F038B237591ED3L, 0xB267ED1940F1C61CL }, // 1e-42
            { 0x6B6C46DEC52F6688L, 0xDF01E85F912E37A3L }, // 1e-41
            { 0x2323AC4B3B3DA015L, 0x8B61313BBABCE2C6L }, // 1e-40
            { 0xABEC975E0A0D081AL, 0xAE397D8AA96C1B77L }, // 1e-39
            { 0x96E7BD358C904A21L, 0xD9C7DCED53C72255L }, // 1e-38
            { 0x7E50D64177DA2E54L, 0x881CEA14545C7575L }, // 1e-37
            { 0xDDE50BD1D5D0B9E9L, 0xAA242499697392D2L }, // 1e-36
            { 0x955E4EC64B44E864L, 0xD4AD2DBFC3D07787L }, // 1e-35
            { 0xBD5AF13BEF0B113EL, 0x84EC3C97DA624AB4L }, // 1e-34
            { 0xECB1AD8AEACDD58EL, 0xA6274BBDD0FADD61L }, // 1e-33
            { 0x67DE18EDA5814AF2L, 0xCFB11EAD453994BAL }, // 1e-32
            { 0x80EACF948770CED7L, 0x81CEB32C4B43FCF4L }, // 1e-31
            { 0xA1258379A94D028DL, 0xA2425FF75E14FC31L }, // 1e-30
            { 0x096EE45813A04330L, 0xCAD2F7F5359A3B3EL }, // 1e-29
            { 0x8BCA9D6E188853FCL, 0xFD87B5F28300CA0DL }, // 1e-28
            { 0x775EA264CF55347DL, 0x9E74D1B791E07E48L }, // 1e-27
            { 0x95364AFE032A819DL, 0xC612062576589DDAL }, // 1e-26
            { 0x3A83DDBD83F52204L, 0xF79687AED3EEC551L }, // 1e-25
            { 0xC4926A9672793542L, 0x9ABE14CD44753B52L }, // 1e-24
            { 0x75B7053C0F178293L, 0xC16D9A0095928A27L }, // 1e-23
            { 0x5324C68B12DD6338L, 0xF1C90080BAF72CB1L }, // 1e-22
            { 0xD3F6FC16EBCA5E03L, 0x971DA05074DA7BEEL }, // 1e-21
            { 0x88F4BB1CA6BCF584L, 0xBCE5086492111AEAL }, // 1e-20
            { 0x2B31E9E3D06C32E5L, 0xEC1E4A7DB69561A5L }, // 1e-19
            { 0x3AFF322E62439FCFL, 0x9392EE8E921D5D07L }, // 1e-18
            { 0x09BEFEB9FAD487C2L, 0xB877AA3236A4B449L }, // 1e-17
            { 0x4C2EBE687989A9B3L, 0xE69594BEC44DE15BL }, // 1e-16
            { 0x0F9D37014BF60A10L, 0x901D7CF73AB0ACD9L }, // 1e-15
            { 0x538484C19EF38C94L, 0xB424DC35095CD80FL }, // 1e-14
            { 0x2865A5F206B06FB9L, 0xE12E13424BB40E13L }, // 1e-13
            { 0xF93F87B7442E45D3L, 0x8CBCCC096F5088CBL }, // 1e-12
            { 0xF78F69A51539D748L, 0xAFEBFF0BCB24AAFEL }, // 1e-11
            { 0xB573440E5A884D1BL, 0xDBE6FECEBDEDD5BEL }, // 1e-10
            { 0x31680A88F8953030L, 0x89705F4136B4A597L }, // 1e-9
            { 0xFDC20D2B36BA7C3DL, 0xABCC77118461CEFCL }, // 1e-8
            { 0x3D32907604691B4CL, 0xD6BF94D5E57A42BCL }, // 1e-7
            { 0xA63F9A49C2C1B10FL, 0x8637BD05AF6C69B5L }, // 1e-6
            { 0x0FCF80DC33721D53L, 0xA7C5AC471B478423L }, // 1e-5
            { 0xD3C36113404EA4A8L, 0xD1B71758E219652BL }, // 1e-4
            { 0x645A1CAC083126E9L, 0x83126E978D4FDF3BL }, // 1e-3
            { 0x3D70A3D70A3D70A3L, 0xA3D70A3D70A3D70AL }, // 1e-2
            { 0xCCCCCCCCCCCCCCCCL, 0xCCCCCCCCCCCCCCCCL }, // 1e-1
            { 0x0000000000000000L, 0x8000000000000000L }, // 1e0
            { 0x0000000000000000L, 0xA000000000000000L }, // 1e1
            { 0x0000000000000000L, 0xC800000000000000L }, // 1e2
            { 0x0000000000000000L, 0xFA00000000000000L }, // 1e3
            { 0x0000000000000000L, 0x9C40000000000000L }, // 1e4
            { 0x0000000000000000L, 0xC350000000000000L }, // 1e5
            { 0x0000000000000000L, 0xF424000000000000L }, // 1e6
            { 0x0000000000000000L, 0x9896800000000000L }, // 1e7
            { 0x0000000000000000L, 0xBEBC200000000000L }, // 1e8
            { 0x0000000000000000L, 0xEE6B280000000000L }, // 1e9
            { 0x0000000000000000L, 0x9502F90000000000L }, // 1e10
            { 0x0000000000000000L, 0xBA43B74000000000L }, // 1e11
            { 0x0000000000000000L, 0xE8D4A51000000000L }, // 1e12
            { 0x0000000000000000L, 0x9184E72A00000000L }, // 1e13
            { 0x0000000000000000L, 0xB5E620F480000000L }, // 1e14
            { 0x0000000000000000L, 0xE35FA931A0000000L }, // 1e15
            { 0x0000000000000000L, 0x8E1BC9BF04000000L }, // 1e16
            { 0x0000000000000000L, 0xB1A2BC2EC5000000L }, // 1e17
            { 0x0000000000000000L, 0xDE0B6B3A76400000L }, // 1e18
            { 0x0000000000000000L, 0x8AC7230489E80000L }, // 1e19
            { 0x0000000000000000L, 0xAD78EBC5AC620000L }, // 1e20
            { 0x0000000000000000L, 0xD8D726B7177A8000L }, // 1e21
            { 0x0000000000000000L, 0x878678326EAC9000L }, // 1e22
            { 0x0000000000000000L, 0xA968163F0A57B400L }, // 1e23
            { 0x0000000000000000L, 0xD3C21BCECCEDA100L }, // 1e24
            { 0x0000000000000000L, 0x84595161401484A0L }, // 1e25
            { 0x0000000000000000L, 0xA56FA5B99019A5C8L }, // 1e26
            { 0x0000000000000000L, 0xCECB8F27F4200F3AL }, // 1e27
            { 0x4000000000000000L, 0x813F3978F8940984L }, // 1e28
            { 0x5000000000000000L, 0xA18F07D736B90BE5L }, // 1e29
            { 0xA400000000000000L, 0xC9F2C9CD04674EDEL }, // 1e30
            { 0x4D00000000000000L, 0xFC6F7C4045812296L }, // 1e31
            { 0xF020000000000000L, 0x9DC5ADA82B70B59DL }, // 1e32
            { 0x6C28000000000000L, 0xC5371912364CE305L }, // 1e33
            { 0xC732000000000000L, 0xF684DF56C3E01BC6L }, // 1e34
            { 0x3C7F400000000000L, 0x9A130B963A6C115CL }, // 1e35
            { 0x4B9F100000000000L, 0xC097CE7BC90715B3L }, // 1e36
            { 0x1E86D40000000000L, 0xF0BDC21ABB48DB20L }, // 1e37
            { 0x1314448000000000L, 0x96769950B50D88F4L }, // 1e38
            { 0x17D955A000000000L, 0xBC143FA4E250EB31L }, // 1e39
            { 0x5DCFAB0800000000L, 0xEB194F8E1AE525FDL }, // 1e40
            { 0x5AA1CAE500000000L, 0x92EFD1B8D0CF37BEL }, // 1e41
            { 0xF14A3D9E40000000L, 0xB7ABC627050305ADL }, // 1e42
            { 0x6D9CCD05D0000000L, 0xE596B7B0C643C719L }, // 1e43
            { 0xE4820023A2000000L, 0x8F7E32CE7BEA5C6FL }, // 1e44
            { 0xDDA2802C8A800000L, 0xB35DBF821AE4F38BL }, // 1e45
            { 0xD50B2037AD200000L, 0xE0352F62A19E306EL }, // 1e46
            { 0x4526F422CC340000L, 0x8C213D9DA502DE45L }, // 1e47
            { 0x9670B12B7F410000L, 0xAF298D050E4395D6L }, // 1e48
            { 0x3C0CDD765F114000L, 0xDAF3F04651D47B4CL }, // 1e49
            { 0xA5880A69FB6AC800L, 0x88D8762BF324CD0FL }, // 1e50
            { 0x8EEA0D047A457A00L, 0xAB0E93B6EFEE0053L }, // 1e51
            { 0x72A4904598D6D880L, 0xD5D238A4ABE98068L }, // 1e52
            { 0x47A6DA2B7F864750L, 0x85A36366EB71F041L }, // 1e53
            { 0x999090B65F67D924L, 0xA70C3C40A64E6C51L }, // 1e54
            { 0xFFF4B4E3F741CF6DL, 0xD0CF4B50CFE20765L }, // 1e55
            { 0xBFF8F10E7A8921A4L, 0x82818F1281ED449FL }, // 1e56
            { 0xAFF72D52192B6A0DL, 0xA321F2D7226895C7L }, // 1e57
            { 0x9BF4F8A69F764490L, 0xCBEA6F8CEB02BB39L }, // 1e58
            { 0x02F236D04753D5B4L, 0xFEE50B7025C36A08L }, // 1e59
            { 0x01D762422C946590L, 0x9F4F2726179A2245L }, // 1e60
            { 0x424D3AD2B7B97EF5L, 0xC722F0EF9D80AAD6L }, // 1e61
            { 0xD2E0898765A7DEB2L, 0xF8EBAD2B84E0D58BL }, // 1e62
            { 0x63CC55F49F88EB2FL, 0x9B934C3B330C8577L }, // 1e63
            { 0x3CBF6B71C76B25FBL, 0xC2781F49FFCFA6D5L }, // 1e64
            { 0x8BEF464E3945EF7AL, 0xF316271C7FC3908AL }, // 1e65
            { 0x97758BF0E3CBB5ACL, 0x97EDD871CFDA3A56L }, // 1e66
            { 0x3D52EEED1CBEA317L, 0xBDE94E8E43D0C8ECL }, // 1e67
            { 0x4CA7AAA863EE4BDDL, 0xED63A231D4C4FB27L }, // 1e68
            { 0x8FE8CAA93E74EF6AL, 0x945E455F24FB1CF8L }, // 1e69
            { 0xB3E2FD538E122B44L, 0xB975D6B6EE39E436L }, // 1e70
            { 0x60DBBCA87196B616L, 0xE7D34C64A9C85D44L }, // 1e71
            { 0xBC8955E946FE31CDL, 0x90E40FBEEA1D3A4AL }, // 1e72
            { 0x6BABAB6398BDBE41L, 0xB51D13AEA4A488DDL }, // 1e73
            { 0xC696963C7EED2DD1L, 0xE264589A4DCDAB14L }, // 1e74
            { 0xFC1E1DE5CF543CA2L, 0x8D7EB76070A08AECL }, // 1e75
            { 0x3B25A55F43294BCBL, 0xB0DE65388CC8ADA8L }, // 1e76
            { 0x49EF0EB713F39EBEL, 0xDD15FE86AFFAD912L }, // 1e77
            { 0x6E3569326C784337L, 0x8A2DBF142DFCC7ABL }, // 1e78
            { 0x49C2C37F07965404L, 0xACB92ED9397BF996L }, // 1e79
            { 0xDC33745EC97BE906L, 0xD7E77A8F87DAF7FBL }, // 1e80
            { 0x69A028BB3DED71A3L, 0x86F0AC99B4E8DAFDL }, // 1e81
            { 0xC40832EA0D68CE0CL, 0xA8ACD7C0222311BCL }, // 1e82
            { 0xF50A3FA490C30190L, 0xD2D80DB02AABD62BL }, // 1e83
            { 0x792667C6DA79E0FAL, 0x83C7088E1AAB65DBL }, // 1e84
            { 0x577001B891185938L, 0xA4B8CAB1A1563F52L }, // 1e85
            { 0xED4C0226B55E6F86L, 0xCDE6FD5E09ABCF26L }, // 1e86
            { 0x544F8158315B05B4L, 0x80B05E5AC60B6178L }, // 1e87
            { 0x696361AE3DB1C721L, 0xA0DC75F1778E39D6L }, // 1e88
            { 0x03BC3A19CD1E38E9L, 0xC913936DD571C84CL }, // 1e89
            { 0x04AB48A04065C723L, 0xFB5878494ACE3A5FL }, // 1e90
            { 0x62EB0D64283F9C76L, 0x9D174B2DCEC0E47BL }, // 1e91
            { 0x3BA5D0BD324F8394L, 0xC45D1DF942711D9AL }, // 1e92
            { 0xCA8F44EC7EE36479L, 0xF5746577930D6500L }, // 1e93
            { 0x7E998B13CF4E1ECBL, 0x9968BF6ABBE85F20L }, // 1e94
            { 0x9E3FEDD8C321A67EL, 0xBFC2EF456AE276E8L }, // 1e95
            { 0xC5CFE94EF3EA101EL, 0xEFB3AB16C59B14A2L }, // 1e96
            { 0xBBA1F1D158724A12L, 0x95D04AEE3B80ECE5L }, // 1e97
            { 0x2A8A6E45AE8EDC97L, 0xBB445DA9CA61281FL }, // 1e98
            { 0xF52D09D71A3293BDL, 0xEA1575143CF97226L }, // 1e99
            { 0x593C2626705F9C56L, 0x924D692CA61BE758L }, // 1e100
            { 0x6F8B2FB00C77836CL, 0xB6E0C377CFA2E12EL }, // 1e101
            { 0x0B6DFB9C0F956447L, 0xE498F455C38B997AL }, // 1e102
            { 0x4724BD4189BD5EACL, 0x8EDF98B59A373FECL }, // 1e103
            { 0x58EDEC91EC2CB657L, 0xB2977EE300C50FE7L }, // 1e104
            { 0x2F2967B66737E3EDL, 0xDF3D5E9BC0F653E1L }, // 1e105
            { 0xBD79E0D20082EE74L, 0x8B865B215899F46CL }, // 1e106
            { 0xECD8590680A3AA11L, 0xAE67F1E9AEC07187L }, // 1e107
            { 0xE80E6F4820CC9495L, 0xDA01EE641A708DE9L }, // 1e108
            { 0x3109058D147FDCDDL, 0x884134FE908658B2L }, // 1e109
            { 0xBD4B46F0599FD415L, 0xAA51823E34A7EEDEL }, // 1e110
            { 0x6C9E18AC7007C91AL, 0xD4E5E2CDC1D1EA96L }, // 1e111
            { 0x03E2CF6BC604DDB0L, 0x850FADC09923329EL }, // 1e112
            { 0x84DB8346B786151CL, 0xA6539930BF6BFF45L }, // 1e113
            { 0xE612641865679A63L, 0xCFE87F7CEF46FF16L }, // 1e114
            { 0x4FCB7E8F3F60C07EL, 0x81F14FAE158C5F6EL }, // 1e115
            { 0xE3BE5E330F38F09DL, 0xA26DA3999AEF7749L }, // 1e116
            { 0x5CADF5BFD3072CC5L, 0xCB090C8001AB551CL }, // 1e117
            { 0x73D9732FC7C8F7F6L, 0xFDCB4FA002162A63L }, // 1e118
            { 0x2867E7FDDCDD9AFAL, 0x9E9F11C4014DDA7EL }, // 1e119
            { 0xB281E1FD541501B8L, 0xC646D63501A1511DL }, // 1e120
            { 0x1F225A7CA91A4226L, 0xF7D88BC24209A565L }, // 1e121
            { 0x3375788DE9B06958L, 0x9AE757596946075FL }, // 1e122
            { 0x0052D6B1641C83AEL, 0xC1A12D2FC3978937L }, // 1e123
            { 0xC0678C5DBD23A49AL, 0xF209787BB47D6B84L }, // 1e124
            { 0xF840B7BA963646E0L, 0x9745EB4D50CE6332L }, // 1e125
            { 0xB650E5A93BC3D898L, 0xBD176620A501FBFFL }, // 1e126
            { 0xA3E51F138AB4CEBEL, 0xEC5D3FA8CE427AFFL }, // 1e127
            { 0xC66F336C36B10137L, 0x93BA47C980E98CDFL }, // 1e128
            { 0xB80B0047445D4184L, 0xB8A8D9BBE123F017L }, // 1e129
            { 0xA60DC059157491E5L, 0xE6D3102AD96CEC1DL }, // 1e130
            { 0x87C89837AD68DB2FL, 0x9043EA1AC7E41392L }, // 1e131
            { 0x29BABE4598C311FBL, 0xB454E4A179DD1877L }, // 1e132
            { 0xF4296DD6FEF3D67AL, 0xE16A1DC9D8545E94L }, // 1e133
            { 0x1899E4A65F58660CL, 0x8CE2529E2734BB1DL }, // 1e134
            { 0x5EC05DCFF72E7F8FL, 0xB01AE745B101E9E4L }, // 1e135
            { 0x76707543F4FA1F73L, 0xDC21A1171D42645DL }, // 1e136
            { 0x6A06494A791C53A8L, 0x899504AE72497EBAL }, // 1e137
            { 0x0487DB9D17636892L, 0xABFA45DA0EDBDE69L }, // 1e138
            { 0x45A9D2845D3C42B6L, 0xD6F8D7509292D603L }, // 1e139
            { 0x0B8A2392BA45A9B2L, 0x865B86925B9BC5C2L }, // 1e140
            { 0x8E6CAC7768D7141EL, 0xA7F26836F282B732L }, // 1e141
            { 0x3207D795430CD926L, 0xD1EF0244AF2364FFL }, // 1e142
            { 0x7F44E6BD49E807B8L, 0x8335616AED761F1FL }, // 1e143
            { 0x5F16206C9C6209A6L, 0xA402B9C5A8D3A6E7L }, // 1e144
            { 0x36DBA887C37A8C0FL, 0xCD036837130890A1L }, // 1e145
            { 0xC2494954DA2C9789L, 0x802221226BE55A64L }, // 1e146
            { 0xF2DB9BAA10B7BD6CL, 0xA02AA96B06DEB0FDL }, // 1e147
            { 0x6F92829494E5ACC7L, 0xC83553C5C8965D3DL }, // 1e148
            { 0xCB772339BA1F17F9L, 0xFA42A8B73ABBF48CL }, // 1e149
            { 0xFF2A760414536EFBL, 0x9C69A97284B578D7L }, // 1e150
            { 0xFEF5138519684ABAL, 0xC38413CF25E2D70DL }, // 1e151
            { 0x7EB258665FC25D69L, 0xF46518C2EF5B8CD1L }, // 1e152
            { 0xEF2F773FFBD97A61L, 0x98BF2F79D5993802L }, // 1e153
            { 0xAAFB550FFACFD8FAL, 0xBEEEFB584AFF8603L }, // 1e154
            { 0x95BA2A53F983CF38L, 0xEEAABA2E5DBF6784L }, // 1e155
            { 0xDD945A747BF26183L, 0x952AB45CFA97A0B2L }, // 1e156
            { 0x94F971119AEEF9E4L, 0xBA756174393D88DFL }, // 1e157
            { 0x7A37CD5601AAB85DL, 0xE912B9D1478CEB17L }, // 1e158
            { 0xAC62E055C10AB33AL, 0x91ABB422CCB812EEL }, // 1e159
            { 0x577B986B314D6009L, 0xB616A12B7FE617AAL }, // 1e160
            { 0xED5A7E85FDA0B80BL, 0xE39C49765FDF9D94L }, // 1e161
            { 0x14588F13BE847307L, 0x8E41ADE9FBEBC27DL }, // 1e162
            { 0x596EB2D8AE258FC8L, 0xB1D219647AE6B31CL }, // 1e163
            { 0x6FCA5F8ED9AEF3BBL, 0xDE469FBD99A05FE3L }, // 1e164
            { 0x25DE7BB9480D5854L, 0x8AEC23D680043BEEL }, // 1e165
            { 0xAF561AA79A10AE6AL, 0xADA72CCC20054AE9L }, // 1e166
            { 0x1B2BA1518094DA04L, 0xD910F7FF28069DA4L }, // 1e167
            { 0x90FB44D2F05D0842L, 0x87AA9AFF79042286L }, // 1e168
            { 0x353A1607AC744A53L, 0xA99541BF57452B28L }, // 1e169
            { 0x42889B8997915CE8L, 0xD3FA922F2D1675F2L }, // 1e170
            { 0x69956135FEBADA11L, 0x847C9B5D7C2E09B7L }, // 1e171
            { 0x43FAB9837E699095L, 0xA59BC234DB398C25L }, // 1e172
            { 0x94F967E45E03F4BBL, 0xCF02B2C21207EF2EL }, // 1e173
            { 0x1D1BE0EEBAC278F5L, 0x8161AFB94B44F57DL }, // 1e174
            { 0x6462D92A69731732L, 0xA1BA1BA79E1632DCL }, // 1e175
            { 0x7D7B8F7503CFDCFEL, 0xCA28A291859BBF93L }, // 1e176
            { 0x5CDA735244C3D43EL, 0xFCB2CB35E702AF78L }, // 1e177
            { 0x3A0888136AFA64A7L, 0x9DEFBF01B061ADABL }, // 1e178
            { 0x088AAA1845B8FDD0L, 0xC56BAEC21C7A1916L }, // 1e179
            { 0x8AAD549E57273D45L, 0xF6C69A72A3989F5BL }, // 1e180
            { 0x36AC54E2F678864BL, 0x9A3C2087A63F6399L }, // 1e181
            { 0x84576A1BB416A7DDL, 0xC0CB28A98FCF3C7FL }, // 1e182
            { 0x656D44A2A11C51D5L, 0xF0FDF2D3F3C30B9FL }, // 1e183
            { 0x9F644AE5A4B1B325L, 0x969EB7C47859E743L }, // 1e184
            { 0x873D5D9F0DDE1FEEL, 0xBC4665B596706114L }, // 1e185
            { 0xA90CB506D155A7EAL, 0xEB57FF22FC0C7959L }, // 1e186
            { 0x09A7F12442D588F2L, 0x9316FF75DD87CBD8L }, // 1e187
            { 0x0C11ED6D538AEB2FL, 0xB7DCBF5354E9BECEL }, // 1e188
            { 0x8F1668C8A86DA5FAL, 0xE5D3EF282A242E81L }, // 1e189
            { 0xF96E017D694487BCL, 0x8FA475791A569D10L }, // 1e190
            { 0x37C981DCC395A9ACL, 0xB38D92D760EC4455L }, // 1e191
            { 0x85BBE253F47B1417L, 0xE070F78D3927556AL }, // 1e192
            { 0x93956D7478CCEC8EL, 0x8C469AB843B89562L }, // 1e193
            { 0x387AC8D1970027B2L, 0xAF58416654A6BABBL }, // 1e194
            { 0x06997B05FCC0319EL, 0xDB2E51BFE9D0696AL }, // 1e195
            { 0x441FECE3BDF81F03L, 0x88FCF317F22241E2L }, // 1e196
            { 0xD527E81CAD7626C3L, 0xAB3C2FDDEEAAD25AL }, // 1e197
            { 0x8A71E223D8D3B074L, 0xD60B3BD56A5586F1L }, // 1e198
            { 0xF6872D5667844E49L, 0x85C7056562757456L }, // 1e199
            { 0xB428F8AC016561DBL, 0xA738C6BEBB12D16CL }, // 1e200
            { 0xE13336D701BEBA52L, 0xD106F86E69D785C7L }, // 1e201
            { 0xECC0024661173473L, 0x82A45B450226B39CL }, // 1e202
            { 0x27F002D7F95D0190L, 0xA34D721642B06084L }, // 1e203
            { 0x31EC038DF7B441F4L, 0xCC20CE9BD35C78A5L }, // 1e204
            { 0x7E67047175A15271L, 0xFF290242C83396CEL }, // 1e205
            { 0x0F0062C6E984D386L, 0x9F79A169BD203E41L }, // 1e206
            { 0x52C07B78A3E60868L, 0xC75809C42C684DD1L }, // 1e207
            { 0xA7709A56CCDF8A82L, 0xF92E0C3537826145L }, // 1e208
            { 0x88A66076400BB691L, 0x9BBCC7A142B17CCBL }, // 1e209
            { 0x6ACFF893D00EA435L, 0xC2ABF989935DDBFEL }, // 1e210
            { 0x0583F6B8C4124D43L, 0xF356F7EBF83552FEL }, // 1e211
            { 0xC3727A337A8B704AL, 0x98165AF37B2153DEL }, // 1e212
            { 0x744F18C0592E4C5CL, 0xBE1BF1B059E9A8D6L }, // 1e213
            { 0x1162DEF06F79DF73L, 0xEDA2EE1C7064130CL }, // 1e214
            { 0x8ADDCB5645AC2BA8L, 0x9485D4D1C63E8BE7L }, // 1e215
            { 0x6D953E2BD7173692L, 0xB9A74A0637CE2EE1L }, // 1e216
            { 0xC8FA8DB6CCDD0437L, 0xE8111C87C5C1BA99L }, // 1e217
            { 0x1D9C9892400A22A2L, 0x910AB1D4DB9914A0L }, // 1e218
            { 0x2503BEB6D00CAB4BL, 0xB54D5E4A127F59C8L }, // 1e219
            { 0x2E44AE64840FD61DL, 0xE2A0B5DC971F303AL }, // 1e220
            { 0x5CEAECFED289E5D2L, 0x8DA471A9DE737E24L }, // 1e221
            { 0x7425A83E872C5F47L, 0xB10D8E1456105DADL }, // 1e222
            { 0xD12F124E28F77719L, 0xDD50F1996B947518L }, // 1e223
            { 0x82BD6B70D99AAA6FL, 0x8A5296FFE33CC92FL }, // 1e224
            { 0x636CC64D1001550BL, 0xACE73CBFDC0BFB7BL }, // 1e225
            { 0x3C47F7E05401AA4EL, 0xD8210BEFD30EFA5AL }, // 1e226
            { 0x65ACFAEC34810A71L, 0x8714A775E3E95C78L }, // 1e227
            { 0x7F1839A741A14D0DL, 0xA8D9D1535CE3B396L }, // 1e228
            { 0x1EDE48111209A050L, 0xD31045A8341CA07CL }, // 1e229
            { 0x934AED0AAB460432L, 0x83EA2B892091E44DL }, // 1e230
            { 0xF81DA84D5617853FL, 0xA4E4B66B68B65D60L }, // 1e231
            { 0x36251260AB9D668EL, 0xCE1DE40642E3F4B9L }, // 1e232
            { 0xC1D72B7C6B426019L, 0x80D2AE83E9CE78F3L }, // 1e233
            { 0xB24CF65B8612F81FL, 0xA1075A24E4421730L }, // 1e234
            { 0xDEE033F26797B627L, 0xC94930AE1D529CFCL }, // 1e235
            { 0x169840EF017DA3B1L, 0xFB9B7CD9A4A7443CL }, // 1e236
            { 0x8E1F289560EE864EL, 0x9D412E0806E88AA5L }, // 1e237
            { 0xF1A6F2BAB92A27E2L, 0xC491798A08A2AD4EL }, // 1e238
            { 0xAE10AF696774B1DBL, 0xF5B5D7EC8ACB58A2L }, // 1e239
            { 0xACCA6DA1E0A8EF29L, 0x9991A6F3D6BF1765L }, // 1e240
            { 0x17FD090A58D32AF3L, 0xBFF610B0CC6EDD3FL }, // 1e241
            { 0xDDFC4B4CEF07F5B0L, 0xEFF394DCFF8A948EL }, // 1e242
            { 0x4ABDAF101564F98EL, 0x95F83D0A1FB69CD9L }, // 1e243
            { 0x9D6D1AD41ABE37F1L, 0xBB764C4CA7A4440FL }, // 1e244
            { 0x84C86189216DC5EDL, 0xEA53DF5FD18D5513L }, // 1e245
            { 0x32FD3CF5B4E49BB4L, 0x92746B9BE2F8552CL }, // 1e246
            { 0x3FBC8C33221DC2A1L, 0xB7118682DBB66A77L }, // 1e247
            { 0x0FABAF3FEAA5334AL, 0xE4D5E82392A40515L }, // 1e248
            { 0x29CB4D87F2A7400EL, 0x8F05B1163BA6832DL }, // 1e249
            { 0x743E20E9EF511012L, 0xB2C71D5BCA9023F8L }, // 1e250
            { 0x914DA9246B255416L, 0xDF78E4B2BD342CF6L }, // 1e251
            { 0x1AD089B6C2F7548EL, 0x8BAB8EEFB6409C1AL }, // 1e252
            { 0xA184AC2473B529B1L, 0xAE9672ABA3D0C320L }, // 1e253
            { 0xC9E5D72D90A2741EL, 0xDA3C0F568CC4F3E8L }, // 1e254
            { 0x7E2FA67C7A658892L, 0x8865899617FB1871L }, // 1e255
            { 0xDDBB901B98FEEAB7L, 0xAA7EEBFB9DF9DE8DL }, // 1e256
            { 0x552A74227F3EA565L, 0xD51EA6FA85785631L }, // 1e257
            { 0xD53A88958F87275FL, 0x8533285C936B35DEL }, // 1e258
            { 0x8A892ABAF368F137L, 0xA67FF273B8460356L }, // 1e259
            { 0x2D2B7569B0432D85L, 0xD01FEF10A657842CL }, // 1e260
            { 0x9C3B29620E29FC73L, 0x8213F56A67F6B29BL }, // 1e261
            { 0x8349F3BA91B47B8FL, 0xA298F2C501F45F42L }, // 1e262
            { 0x241C70A936219A73L, 0xCB3F2F7642717713L }, // 1e263
            { 0xED238CD383AA0110L, 0xFE0EFB53D30DD4D7L }, // 1e264
            { 0xF4363804324A40AAL, 0x9EC95D1463E8A506L }, // 1e265
            { 0xB143C6053EDCD0D5L, 0xC67BB4597CE2CE48L }, // 1e266
            { 0xDD94B7868E94050AL, 0xF81AA16FDC1B81DAL }, // 1e267
            { 0xCA7CF2B4191C8326L, 0x9B10A4E5E9913128L }, // 1e268
            { 0xFD1C2F611F63A3F0L, 0xC1D4CE1F63F57D72L }, // 1e269
            { 0xBC633B39673C8CECL, 0xF24A01A73CF2DCCFL }, // 1e270
            { 0xD5BE0503E085D813L, 0x976E41088617CA01L }, // 1e271
            { 0x4B2D8644D8A74E18L, 0xBD49D14AA79DBC82L }, // 1e272
            { 0xDDF8E7D60ED1219EL, 0xEC9C459D51852BA2L }, // 1e273
            { 0xCABB90E5C942B503L, 0x93E1AB8252F33B45L }, // 1e274
            { 0x3D6A751F3B936243L, 0xB8DA1662E7B00A17L }, // 1e275
            { 0x0CC512670A783AD4L, 0xE7109BFBA19C0C9DL }, // 1e276
            { 0x27FB2B80668B24C5L, 0x906A617D450187E2L }, // 1e277
            { 0xB1F9F660802DEDF6L, 0xB484F9DC9641E9DAL }, // 1e278
            { 0x5E7873F8A0396973L, 0xE1A63853BBD26451L }, // 1e279
            { 0xDB0B487B6423E1E8L, 0x8D07E33455637EB2L }, // 1e280
            { 0x91CE1A9A3D2CDA62L, 0xB049DC016ABC5E5FL }, // 1e281
            { 0x7641A140CC7810FBL, 0xDC5C5301C56B75F7L }, // 1e282
            { 0xA9E904C87FCB0A9DL, 0x89B9B3E11B6329BAL }, // 1e283
            { 0x546345FA9FBDCD44L, 0xAC2820D9623BF429L }, // 1e284
            { 0xA97C177947AD4095L, 0xD732290FBACAF133L }, // 1e285
            { 0x49ED8EABCCCC485DL, 0x867F59A9D4BED6C0L }, // 1e286
            { 0x5C68F256BFFF5A74L, 0xA81F301449EE8C70L }, // 1e287
            { 0x73832EEC6FFF3111L, 0xD226FC195C6A2F8CL }, // 1e288
    };
}