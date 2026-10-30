package com.abhishekraj.BookMySeat.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
		info = @Info(title = "BookMySeat API", version = "v1",
				description = """
						Movie ticket booking API that guarantees one booking per seat under concurrent load.

						**Try it:** call `POST /api/auth/login` with the demo account `demo@bookmyseat.dev` / `DemoPass123`, 						copy `accessToken`, click **Authorize**, then hold and confirm seats. 						Source: [github.com/Abhishek360007/BookMySeat](https://github.com/Abhishek360007/BookMySeat)
						"""),
		security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
