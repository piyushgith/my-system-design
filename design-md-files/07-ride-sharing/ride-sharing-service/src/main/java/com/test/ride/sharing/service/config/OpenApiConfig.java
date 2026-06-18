package com.test.ride.sharing.service.config;

import com.test.ride.sharing.service.web.DevAuthSupport;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String DEV_AUTH_SCHEME = "devAuth";

    @Bean
    public OpenAPI rideSharingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ride Sharing Service API")
                        .version("v1")
                        .description("""
                                V1 ride-sharing modular monolith — Bangalore demo data, mock external services.

                                **Dev auth:** click **Authorize** and set `X-Uid` to `rider` or `driver`.
                                Legacy headers `X-User-Id` / `X-User-Role` still work for curl.
                                OTP endpoints are public; mock OTP is `123456`.
                                """)
                        .contact(new Contact().name("Ride Sharing Service")))
                .addSecurityItem(new SecurityRequirement().addList(DEV_AUTH_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(DEV_AUTH_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(DevAuthSupport.HEADER_UID)
                                .description("Dev shortcut: `rider` or `driver`")));
    }
}
