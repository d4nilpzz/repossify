package dev.d4nilpzz.params;

import java.lang.reflect.Field;

public class RepossifyHelp {
    public static void print(Class<?> argsClass) {
        System.out.println("Options:");

        for (Field field : argsClass.getDeclaredFields()) {
            Param param = field.getAnnotation(Param.class);
            if (param == null) continue;

            String names = String.join(", ", param.names());
            String desc = param.description();

            System.out.printf("  %-30s %s%n", names, desc);
        }
    }
}
