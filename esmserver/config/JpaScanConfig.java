package io.github.rladmstj.esmserver.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackages = "io.github.rladmstj.esmserver.model")
@EnableJpaRepositories(basePackages = "io.github.rladmstj.esmserver.repository")
public class JpaScanConfig { }
