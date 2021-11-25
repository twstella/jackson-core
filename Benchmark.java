
import java.time.LocalTime;

import com.fasterxml.jackson.core.io.NumberInput;

public class Benchmark {
    public static void main(String[] args){
        // 15 test cases
        String[] floats = {"1245.63e-2","12.5","-23561.4324e5",".235E4","123535.35226783412E-4","-1465678684576765.4353e1","0.00001","-.45346","1451.6743435e-5","34524.62436","-763457.2435","-1.0e3","0.0","0.0E3","345264.6835e3"};
        
        double[] builtInParse = new double[floats.length];
        int startSec;
        int countEqual = 0;
        // result of built in string to double parser
        System.out.println("-------Built In parse to double-------");
        
        for (int i = 0; i < floats.length; i++) {
            System.out.print("Test " + (i + 1) + ": ");
            startSec = LocalTime.now().getNano();
            builtInParse[i] = Double.parseDouble(floats[i]);
            int lapsSec = LocalTime.now().getNano();
            System.out.println((lapsSec-startSec) / 1000.0 + "ms");
        }
        // result of EiselLemire algorithm
        System.out.println("-------EiselLemire algorithm-------");
        for(int i = 0;i < floats.length;i++){
            System.out.print("Test " + (i + 1) + ": ");
            startSec = LocalTime.now().getNano();
            double tmp = NumberInput.parseDouble(floats[i]);
            int lapsSec = LocalTime.now().getNano();
            System.out.println((lapsSec-startSec) / 1000.0 + "ms");
            // check if the both results match
            if(tmp == builtInParse[i]) {
                countEqual++;
            } 
        }
        System.out.println("-------result-------");
        // number of results matched out of total test case
        System.out.println(countEqual + " out of " + floats.length+ " succeed");
    }
}
