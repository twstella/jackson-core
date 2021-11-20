package com.fasterxml.jackson.core.io;
/*--------------------problems-------------------
    HELP!!!!
    
    제가 git hub를 많이 써보지 못해서 이렇게 해도 되는지 모르겠네요....
    jackson-core git hub에서 fork 해서 제  repo로 가져왔는데 
    
    아직 제가 floating point 나타내는 것 이해가 완벽하게 된 것 같지가 않아서
    공유 해주신 블로그에 있는 
    "1.23e45" becomes (123 * (10 ** 43))
    "67800.0" becomes (678 * (10 ** 2))
    "3.14159" becomes (314159 * (10 ** -5))
    갖고 작성해봤는데 맞는지 모르겠네요...
    
    그리고 결과로 나오는 (mantissa,exponential)가 128bit여야 할 것 같아서 
    (long,long) 으로 나오는게 했는데 맞는지 모르겠네요....
    
    현재 long 범위를 벗어나면 overflow 때문에 결과가 이상하게 나와요....
    //long overflow
    //1243565768679065.1247305834
    //(6530428241539998826,-10)
*/
import java.math.BigDecimal;

public final class NumberInput
{
    /**
     * Textual representation of a double constant that can cause nasty problems
     * with JDK (see http://www.exploringbinary.com/java-hangs-when-converting-2-2250738585072012e-308).
     */
    public final static String NASTY_SMALL_DOUBLE = "2.2250738585072012e-308";

    /**
     * Constants needed for parsing longs from basic int parsing methods
     */
    final static long L_BILLION = 1000000000;

    final static String MIN_LONG_STR_NO_SIGN = String.valueOf(Long.MIN_VALUE).substring(1);
    final static String MAX_LONG_STR = String.valueOf(Long.MAX_VALUE);

    /**
     * Fast method for parsing unsigned integers that are known to fit into
     * regular 32-bit signed int type. This means that length is
     * between 1 and 9 digits (inclusive) and there is no sign character.
     *<p>
     * Note: public to let unit tests call it; not meant to be used by any
     * code outside this package.
     *
     * @param ch Buffer that contains integer value to decode
     * @param off Offset of the first digit character in buffer
     * @param len Length of the number to decode (in characters)
     *
     * @return Decoded {@code int} value
     */
    public static int parseInt(char[] ch, int off, int len)
    {
        int num = ch[off + len - 1] - '0';
        
        switch(len) {
        case 9: 
          num += (ch[off++] - '0') * 100000000;
        case 8: 
          num += (ch[off++] - '0') * 10000000;
        case 7: 
          num += (ch[off++] - '0') * 1000000;
        case 6: 
          num += (ch[off++] - '0') * 100000;
        case 5: 
          num += (ch[off++] - '0') * 10000;
        case 4: 
          num += (ch[off++] - '0') * 1000;
        case 3: 
          num += (ch[off++] - '0') * 100;
        case 2: 
          num += (ch[off] - '0') * 10;
        }
        return num;
    }

    /**
     * Helper method to (more) efficiently parse integer numbers from
     * String values. Input String must be simple Java integer value.
     * No range checks are made to verify that the value fits in 32-bit Java {@code int}:
     * caller is expected to only calls this in cases where this can be guaranteed
     * (basically: number of digits does not exceed 9)
     *<p>
     * NOTE: semantics differ significantly from {@link #parseInt(char[], int, int)}.
     *
     * @param s String that contains integer value to decode
     *
     * @return Decoded {@code int} value
     */
    public static int parseInt(String s)
    {
        /* Ok: let's keep strategy simple: ignoring optional minus sign,
         * we'll accept 1 - 9 digits and parse things efficiently;
         * otherwise just defer to JDK parse functionality.
         */
        char c = s.charAt(0);
        int len = s.length();
        boolean neg = (c == '-');
        int offset = 1;
        // must have 1 - 9 digits after optional sign:
        // negative?
        if (neg) {
            if (len == 1 || len > 10) {
                return Integer.parseInt(s);
            }
            c = s.charAt(offset++);
        } else {
            if (len > 9) {
                return Integer.parseInt(s);
            }
        }
        if (c > '9' || c < '0') {
            return Integer.parseInt(s);
        }
        int num = c - '0';
        if (offset < len) {
            c = s.charAt(offset++);
            if (c > '9' || c < '0') {
                return Integer.parseInt(s);
            }
            num = (num * 10) + (c - '0');
            if (offset < len) {
                c = s.charAt(offset++);
                if (c > '9' || c < '0') {
                    return Integer.parseInt(s);
                }
                num = (num * 10) + (c - '0');
                // Let's just loop if we have more than 3 digits:
                if (offset < len) {
                    do {
                        c = s.charAt(offset++);
                        if (c > '9' || c < '0') {
                            return Integer.parseInt(s);
                        }
                        num = (num * 10) + (c - '0');
                    } while (offset < len);
                }
            }
        }
        return neg ? -num : num;
    }

    public static long parseLong(char[] ch, int off, int len)
    {
        // Note: caller must ensure length is [10, 18]
        int len1 = len-9;
        long val = parseInt(ch, off, len1) * L_BILLION;
        return val + (long) parseInt(ch, off+len1, 9);
    }

    /**
     * Similar to {@link #parseInt(String)} but for {@code long} values.
     *
     * @param s String that contains {@code long} value to decode
     *
     * @return Decoded {@code long} value
     */
    public static long parseLong(String s)
    {
        // Ok, now; as the very first thing, let's just optimize case of "fake longs";
        // that is, if we know they must be ints, call int parsing
        int length = s.length();
        if (length <= 9) {
            return (long) parseInt(s);
        }
        // !!! TODO: implement efficient 2-int parsing...
        return Long.parseLong(s);
    }

    /**
     * Helper method for determining if given String representation of
     * an integral number would fit in 64-bit Java long or not.
     * Note that input String must NOT contain leading minus sign (even
     * if 'negative' is set to true).
     *
     * @param ch Buffer that contains long value to check
     * @param off Offset of the first digit character in buffer
     * @param len Length of the number to decode (in characters)
     * @param negative Whether original number had a minus sign (which is
     *    NOT passed to this method) or not
     *
     * @return {@code True} if specified String representation is within Java
     *   {@code long} range; {@code false} if not.
     */
    public static boolean inLongRange(char[] ch, int off, int len,
            boolean negative)
    {
        String cmpStr = negative ? MIN_LONG_STR_NO_SIGN : MAX_LONG_STR;
        int cmpLen = cmpStr.length();
        if (len < cmpLen) return true;
        if (len > cmpLen) return false;

        for (int i = 0; i < cmpLen; ++i) {
            int diff = ch[off+i] - cmpStr.charAt(i);
            if (diff != 0) {
                return (diff < 0);
            }
        }
        return true;
    }

    /**
     * Similar to {@link #inLongRange(char[],int,int,boolean)}, but
     * with String argument
     *
     * @param s String that contains {@code long} value to check
     * @param negative Whether original number had a minus sign (which is
     *    NOT passed to this method) or not
     *
     * @return {@code True} if specified String representation is within Java
     *   {@code long} range; {@code false} if not.
     */
    public static boolean inLongRange(String s, boolean negative)
    {
        String cmp = negative ? MIN_LONG_STR_NO_SIGN : MAX_LONG_STR;
        int cmpLen = cmp.length();
        int alen = s.length();
        if (alen < cmpLen) return true;
        if (alen > cmpLen) return false;

        // could perhaps just use String.compareTo()?
        for (int i = 0; i < cmpLen; ++i) {
            int diff = s.charAt(i) - cmp.charAt(i);
            if (diff != 0) {
                return (diff < 0);
            }
        }
        return true;
    }

    public static int parseAsInt(String s, int def)
    {
        if (s == null) {
            return def;
        }
        s = s.trim();
        int len = s.length();
        if (len == 0) {
            return def;
        }
        // One more thing: use integer parsing for 'simple'
        int i = 0;
        // skip leading sign, if any
        final char sign = s.charAt(0);
        if (sign == '+') { // for plus, actually physically remove
            s = s.substring(1);
            len = s.length();
        } else if (sign == '-') { // minus, just skip for checks, must retain
            i = 1;
        }
        for (; i < len; ++i) {
            char c = s.charAt(i);
            // if other symbols, parse as Double, coerce
            if (c > '9' || c < '0') {
                try {
                    return (int) parseDouble(s);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) { }
        return def;
    }

    public static long parseAsLong(String s, long def)
    {
        if (s == null) {
            return def;
        }
        s = s.trim();
        int len = s.length();
        if (len == 0) {
            return def;
        }
        // One more thing: use long parsing for 'simple'
        int i = 0;
        // skip leading sign, if any
        final char sign = s.charAt(0);
        if (sign == '+') { // for plus, actually physically remove
            s = s.substring(1);
            len = s.length();
        } else if (sign == '-') { // minus, just skip for checks, must retain
            i = 1;
        }
        for (; i < len; ++i) {
            char c = s.charAt(i);
            // if other symbols, parse as Double, coerce
            if (c > '9' || c < '0') {
                try {
                    return (long) parseDouble(s);
                } catch (NumberFormatException e) {
                    return def;
                }
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) { }
        return def;
    }
    
    public static double parseAsDouble(String s, double def)
    {
        if (s == null) { return def; }
        s = s.trim();
        int len = s.length();
        if (len == 0) {
            return def;
        }
        try {
            return parseDouble(s);
        } catch (NumberFormatException e) { }
        return def;
    }
    //code added for parsing starting from here
    //helper functions
    static long helper_f(long m,long s,char d){
        switch (d){
            case '0':
                break;
            case '1':
                m+=s;
                break;
            case '2':
                m+=2l*s;
                break;
            case '3':
                m+=3l*s;
                break;
            case '4':
                m+=4l*s;
                break;
            case '5':
                m+=5l*s;
                break;
            case '6':
                m+=6l*s;
                break;
            case '7':
                m+=7l*s;
                break;
            case '8':
                m+=8l*s;
                break;
            case '9':
                m+=9l*s;
                break;
            default:
                System.out.println("not a digit");
        }
        return m;
    }
    /*
    static int helper_f(int m,int s,char d){
        switch (d){
            case '0':
                break;
            case '1':
                m+=s;
                break;
            case '2':
                m+=2l*s;
                break;
            case '3':
                m+=3l*s;
                break;
            case '4':
                m+=4l*s;
                break;
            case '5':
                m+=5l*s;
                break;
            case '6':
                m+=6l*s;
                break;
            case '7':
                m+=7l*s;
                break;
            case '8':
                m+=8l*s;
                break;
            case '9':
                m+=9l*s;
                break;
            default:
                System.out.println("not a digit");
        }
        return m;
    }
    */
    //when character matches e or E then calculate e
    static long helper_expo(long e,long s,String inp,int j,int p,long m){
        if(m==0l) return -p;
        if(inp.charAt(j)=='-'){
            s=-1L;
            j++;
        }
        switch (inp.charAt(j)){
            case '0':
                e=0;
                break;
            case '1':
                e=s;
                break;
            case '2':
                e=2l*s;
                break;
            case '3':
                e=3l*s;
                break;
            case '4':
                e=4l*s;
                break;
            case '5':
                e=5l*s;
                break;
            case '6':
                e=6l*s;
                break;
            case '7':
                e=7l*s;
                break;
            case '8':
                e=8l*s;
                break;
            case '9':
                e=9l*s;
                break;
            default:
                System.out.println("not a digit");
        }
        j++;
        while(j<inp.length()){
            e=helper_f(10*e,s,inp.charAt(j));
            j++;
        } 
        return e;
    }
    //class for  mantissa and exponential
    static class Fp{
        long mantissa;
        long expo;
        Fp(long man,long exp){
            mantissa=man;
            expo =exp;
        }
        public long getMantissa(){
            return mantissa;
        }
        public long getExpo(){
            return expo;
        }
    }
    public static double parseDouble(String s) throws NumberFormatException {
        // [JACKSON-486]: avoid some nasty float representations... but should it be MIN_NORMAL or MIN_VALUE?
        /* as per [JACKSON-827], let's use MIN_VALUE as it is available on all JDKs; normalized
         * only in JDK 1.6. In practice, should not really matter.
         */
        if (NASTY_SMALL_DOUBLE.equals(s)) {
             return Double.MIN_VALUE;
        }
        long m=0l;
            int p=0;
            int j=0;
            long flag=1l;
            long e=0L;
            long sign=1L;
            boolean stbydp=false;
            //mantissa minus 확인
            if(s.charAt(j)=='-'){
                flag=-1l;
                j++;
            }
            // - 이후 또는 string의 첫 글자 확인
            switch (s.charAt(j)){
                case '0':
                    break;
                case '1':
                    m=flag;
                    break;
                case '2':
                    m=2l*flag;
                    break;
                case '3':
                    m=3l*flag;
                    break;
                case '4':
                    m=4l*flag;
                    break;
                case '5':
                    m=5l*flag;
                    break;
                case '6':
                    m=6l*flag;
                    break;
                case '7':
                    m=7l*flag;
                    break;
                case '8':
                    m=8l*flag;
                    break;
                case '9':
                    m=9l*flag;
                    break;
                //.으로 시작하면 m을 0으로 두고 stbydp(start by digit pointer)를 true로
                case '.':
                    m=0L;
                    stbydp=true;
                    break;
                default:
                    System.out.println("not a digit");
            }
            j++;
            
            mantissa:while(j<s.length()){
                //e나 E를 만났을 때
                if(s.charAt(j)=='e'||s.charAt(j)=='E'){
                    j++;
                    e=helper_expo(e,sign,s,j,p,m);
                    break;
                }
                //digit pointer를 만났을 때
                if(s.charAt(j)=='.'){
                    j++;
                    while(j<s.length()){
                        //decimal point 이후에 e or E를 만났을 때
                        if(s.charAt(j)=='e'||s.charAt(j)=='E'){
                            j++;
                            e=helper_expo(e,sign,s,j,p,m);
                            break mantissa;
                        }
                        long tmp = m;
                        int ptmp=p;
                        //소수점 이후 0이 아닐 때까지 go
                        while(s.charAt(j)=='0'){
                            ptmp++;
                            tmp= helper_f(10l*tmp,flag,s.charAt(j));
                            j++;
                            if(j==s.length()) break;
                        }
                        //소수점 이후에 0밖에 없을 때
                        if(j==s.length()){
                            break mantissa;
                        }
                        else{
                            //.0이후에 e or E를 만났을때
                            if(s.charAt(j)=='e'||s.charAt(j)=='E'){
                                j++;
                                e=helper_expo(e,sign,s,j,p,m);
                                break mantissa;
                            }
                            //.0 이후에 다른 수를 만났을 때
                            m=tmp;
                            p=ptmp;
                            p++;
                            m=helper_f(10l*m,flag,s.charAt(j));
                            j++;
                        }
                       
                    }
                }
                else{
                    //decimal point로 시작했을 때 
                    if(stbydp) p++;
                    //decimal point 전에 나오는 digit에 대하여
                    m=helper_f(10l*m,flag,s.charAt(j));
                    j++;
                }
            }
        //원래 string print
        System.out.println(s);
        //result print
        System.out.println("("+m+","+(e-p)+")");
        //datatype으로 
        Fp fp = new Fp(m,(e-p));
        return Double.parseDouble(s);
    }
    //to here
    
    //Original code
    //public static double parseDouble(String s) throws //NumberFormatException {
        // [JACKSON-486]: avoid some nasty float representations... but should it be MIN_NORMAL or MIN_VALUE?
        /* as per [JACKSON-827], let's use MIN_VALUE as it is available on all JDKs; normalized
         * only in JDK 1.6. In practice, should not really matter.
         */
    //    if (NASTY_SMALL_DOUBLE.equals(s)) {
    //         return Double.MIN_VALUE;
    //    }
    //    return Double.parseDouble(s);
    //}

    public static BigDecimal parseBigDecimal(String s) throws NumberFormatException {
        return BigDecimalParser.parse(s);
    }

    public static BigDecimal parseBigDecimal(char[] ch, int off, int len) throws NumberFormatException {
        return BigDecimalParser.parse(ch, off, len);
    }

    public static BigDecimal parseBigDecimal(char[] ch) throws NumberFormatException {
        return BigDecimalParser.parse(ch);
    }
}
