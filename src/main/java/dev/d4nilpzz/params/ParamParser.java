package dev.d4nilpzz.params;

import java.lang.reflect.Field;
import java.util.List;

public class ParamParser {
    public static void parse(String[] args, Object target) {
        List<String> list = List.of(args);

        for (Field field : target.getClass().getDeclaredFields()) {
            Param param = field.getAnnotation(Param.class);
            if (param == null) continue;

            for (String name : param.names()) {
                int i = list.indexOf(name);
                if (i == -1) continue;

                try {
                    field.setAccessible(true);

                    if (field.getType() == boolean.class) {
                        field.setBoolean(target, true);
                    } else if (i + 1 < list.size()) {
                        field.set(target, list.get(i + 1));
                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}
