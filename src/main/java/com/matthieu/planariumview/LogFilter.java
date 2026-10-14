package com.matthieu.planariumview;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.StringMatchFilter;

/**
 * TEMPORAIRE : retire du log un avertissement de Dynamic Surroundings repete a chaque image
 * ("Fog calculator '...' reporting invalid fog range (start ..., end ...)"), qui peut remplir le log
 * de dizaines de Mo dans le Nether avec une grande distance de rendu. Seul ce message est retire.
 */
public final class LogFilter {
    private static final String MESSAGE = "reporting invalid fog range";

    private LogFilter() {
    }

    public static void install() {
        try {
            Filter filter = StringMatchFilter.newBuilder()
                    .setMatchString(MESSAGE)
                    .setOnMatch(Filter.Result.DENY)
                    .setOnMismatch(Filter.Result.NEUTRAL)
                    .build();
            java.util.Set<LoggerContext> contexts = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            if (LogManager.getContext(false) instanceof LoggerContext context) {
                contexts.add(context);
            }
            if (LogManager.getLogger("dsurround") instanceof org.apache.logging.log4j.core.Logger logger) {
                contexts.add(logger.getContext());
            }
            for (LoggerContext context : contexts) {
                context.getConfiguration().addFilter(filter);
                context.updateLoggers();
            }
        } catch (Throwable e) {
            // sans filtre, le log garde simplement ces messages
        }
    }
}
