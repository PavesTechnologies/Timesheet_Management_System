package com.intranet.util.mail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MailTransportWiringTest {

    @Configuration
    static class Stubs {
        @Bean RestTemplate restTemplate() { return new RestTemplate(); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(Stubs.class)
            .withBean(SmtpMailTransport.class)
            .withBean(GraphTokenProvider.class)
            .withBean(GraphMailTransport.class);

    @Test
    void defaultsToSmtp() {
        runner.withPropertyValues("spring.mail.host=smtp.gmail.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(MailTransport.class)).isInstanceOf(SmtpMailTransport.class);
            assertThat(ctx.getBeansOfType(GraphMailTransport.class)).isEmpty();
        });
    }

    @Test
    void gmailSelectsSmtp() {
        runner.withPropertyValues("email.service=gmail", "spring.mail.host=smtp.gmail.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(MailTransport.class)).isInstanceOf(SmtpMailTransport.class);
        });
    }

    @Test
    void azureSelectsGraph() {
        runner.withPropertyValues(
                "email.service=azure",
                "azure.tenant-id=t", "azure.client-id=c", "azure.client-secret=s",
                "azure.graph.sender=noreply@example.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(MailTransport.class)).isInstanceOf(GraphMailTransport.class);
            assertThat(ctx.getBeansOfType(SmtpMailTransport.class)).isEmpty();
        });
    }

    @Test
    void azureWithBlankKeysFailsFast() {
        runner.withPropertyValues("email.service=azure").run(ctx ->
                assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining("AZURE_TENANT_ID must be set when EMAIL_SERVICE=azure"));
    }
}
