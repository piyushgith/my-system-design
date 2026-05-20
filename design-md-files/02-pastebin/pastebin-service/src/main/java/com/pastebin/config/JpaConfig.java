package com.pastebin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = {
        "com.pastebin.paste.infrastructure.persistence",
        "com.pastebin.identity.infrastructure.persistence"
})
public class JpaConfig {
}
