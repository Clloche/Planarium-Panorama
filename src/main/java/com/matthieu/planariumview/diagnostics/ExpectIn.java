package com.matthieu.planariumview.diagnostics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bilan des mixins (voir MixinHealth) : ce crochet n'est attendu que dans ces cibles de son mixin, et pas dans les autres.
 * Nom complet ou simple de la classe ({@code "BubbleParticle"}). Sans cette annotation, il est attendu dans chaque cible.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ExpectIn {
    String[] value();
}
