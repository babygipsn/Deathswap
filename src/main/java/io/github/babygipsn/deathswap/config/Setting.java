package io.github.babygipsn.deathswap.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a config field as editable through commands, the chest menu and the client screen.
 * Numeric bounds are inclusive; {@code step} is the increment used by menu buttons.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Setting {
	double min() default -Double.MAX_VALUE;

	double max() default Double.MAX_VALUE;

	double step() default 1;

	/** Item id used as the icon in the chest menu. */
	String icon() default "minecraft:paper";
}
