
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Random;

public class JsonTest {
    protected final static int MODE_INPUT_STREAM = 0;
    protected final static int MODE_INPUT_STREAM_THROTTLED = 1;
    protected final static int MODE_READER = 2;
    protected final static int MODE_DATA_INPUT = 3;

    protected final static JsonFactory JSON_FACTORY = new JsonFactory();

    protected static JsonParser createParser(String input) throws IOException {
        return JSON_FACTORY.createParser(new StringReader(input));
    }

    protected static void assertToken(JsonToken expToken, JsonToken actToken) {
        if (actToken != expToken) {
            System.out.println("Expected token " + expToken + ", current token " + actToken);
        }
    }

    protected static void assertEquals(double d, double e) {
        if (d != e) {
            System.out.println("Expected double " + d + ", current double " + e);
        }
    }

    protected static void testDouble(double d) throws IOException {
        String DOC = "[" + d + "]";
        JsonParser p = createParser(DOC);

        assertToken(JsonToken.START_ARRAY, p.nextToken());
        assertToken(JsonToken.VALUE_NUMBER_FLOAT, p.nextToken());
        assertEquals(d, p.getDoubleValue());
    }

    public static void main(String[] args) {

        Random rnd = new Random();

        for (int i = 0; i < 10000; i++) {
            try {
                testDouble(rnd.nextDouble() * 100);
            } catch (IOException e) {
                System.out.println("ioexception");
            }

        }
    }
}
