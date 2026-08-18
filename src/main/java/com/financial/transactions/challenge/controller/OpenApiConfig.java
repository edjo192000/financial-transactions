package com.financial.transactions.challenge.controller;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionApiInfo() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transaction Execution API")
                        .description("REST API for executing and querying financial "
                                + "transactions (credit/debit) against an external provider. "
                                + "Backend Challenge — Spin.")
                        .version("1.0")
                        .contact(new Contact().name("Backend Challenge Submission")));
    }
}
