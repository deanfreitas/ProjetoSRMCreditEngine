package br.com.srm.creditengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    /**
     * A descricao carrega as premissas que mudam o resultado do calculo (arredondamento,
     * unidade do prazo, qual cotacao vale, contrato de idempotencia). Quem integra precisa
     * saber disso antes da primeira chamada - e o valor de face que "nao fecha" por um
     * centavo quase sempre e premissa diferente, nao bug.
     */
    @Bean
    public OpenAPI creditEngineOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SRM Credit Engine API")
                .version("v1")
                .description("""
                        Precificacao e liquidacao de recebiveis multimoedas (BRL/USD).

                        Premissas que afetam os valores (detalhe em SPEC.md):
                        * valor presente = face / (1 + taxa base + spread) ^ prazo, com prazo em meses inteiros
                          (mes comercial de 30 dias, fracao arredondada para cima);
                        * arredondamento half-even, 2 casas, aplicado uma unica vez no resultado final de cada moeda;
                        * cross-currency converte o valor presente em BRL ja arredondado;
                        * a cotacao usada e a vigente no momento da liquidacao e fica congelada no registro.

                        Convencoes da API:
                        * quantias e taxas trafegam como string para nao perder precisao decimal;
                        * POST /api/v1/settlements exige o header Idempotency-Key: 201 na primeira execucao,
                          200 na repeticao da mesma chave, 409 se a chave for reusada com outro payload;
                        * erro nunca responde 200; o corpo segue RFC 9457 (application/problem+json) com o campo errorCode.
                        """)
                .contact(new Contact().name("Mesa de operacoes SRM"))
                .license(new License().name("Uso interno")));
    }
}
