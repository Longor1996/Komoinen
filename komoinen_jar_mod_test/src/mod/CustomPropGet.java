package mod;

import java.util.function.Function;

public class CustomPropGet implements Function<String, String> {

    public String apply(String str) {
        return "prop-get:"+str;
    }

}
