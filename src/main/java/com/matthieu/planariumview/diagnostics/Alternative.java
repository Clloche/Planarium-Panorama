package com.matthieu.planariumview.diagnostics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bilan des mixins (voir MixinHealth) : variantes d'un meme crochet selon la version d'un mod. Tous les crochets (et toutes
 * leurs cibles) du meme groupe, dans le meme dossier de compatibilite, comptent pour une seule attente : il suffit qu'un
 * seul soit injecte. Seul sur un crochet a plusieurs cibles, le groupe veut dire « au moins une de ses cibles ».
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Alternative {
    String value();
}
