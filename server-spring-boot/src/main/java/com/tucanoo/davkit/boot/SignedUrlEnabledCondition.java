package com.tucanoo.davkit.boot;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** True when at least one {@code davkit.signed-url.keys.<id>} is configured (a Map, so {@code @ConditionalOnProperty} cannot see it). */
class SignedUrlEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("davkit.signed-url.keys", Bindable.mapOf(String.class, String.class))
                .map(m -> !m.isEmpty())
                .orElse(false);
    }
}
