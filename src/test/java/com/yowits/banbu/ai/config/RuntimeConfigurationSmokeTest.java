package com.yowits.banbu.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeConfigurationSmokeTest {

    @Test
    void applicationYamlKeepsCurrentRuntimeDefaults() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.application.name")).isEqualTo("gin-airouter");
        assertThat(properties.getProperty("ai.default-model")).isEqualTo("kimi-main:kimi-k2.5");
        assertThat(properties.getProperty("ai.policy.default-policy.per-route-max-attempts")).isEqualTo("3");
        assertThat(properties.getProperty("ai.policy.default-policy.routing-mode")).isEqualTo("sequential");
        assertThat(properties.getProperty("eureka.client.register-with-eureka")).isEqualTo("true");
        assertThat(properties.getProperty("eureka.instance.prefer-ip-address")).isEqualTo("true");
    }

    @Test
    void pomKeepsExecutableJarAndEurekaDependencies() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("<artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>");
        assertThat(pom).contains("<artifactId>spring-boot-maven-plugin</artifactId>");
        assertThat(pom).contains("<goal>repackage</goal>");
    }
}
