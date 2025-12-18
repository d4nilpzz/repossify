package dev.d4nilpzz.params;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Param {
    String[] names();
    String description() default "";
}
