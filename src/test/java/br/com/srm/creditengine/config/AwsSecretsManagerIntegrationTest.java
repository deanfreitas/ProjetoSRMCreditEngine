package br.com.srm.creditengine.config;

import io.awspring.cloud.secretsmanager.SecretsManagerPropertySource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios e de integracao da leitura de credenciais criticas (como as do banco de
 * dados) gerenciadas via AWS Secrets Manager.
 */
class AwsSecretsManagerIntegrationTest {

    @Test
    @DisplayName("Carrega credenciais de banco de dados a partir de payload JSON no Secrets Manager")
    void shouldLoadDatabaseCredentialsFromJsonSecret() {
        SecretsManagerClient client = Mockito.mock(SecretsManagerClient.class);
        String secretJson = """
                {
                  "spring.datasource.username": "srm_production_user",
                  "spring.datasource.password": "SuperSecretPassword123!",
                  "spring.datasource.url": "jdbc:postgresql://aurora.srm.internal:5432/srm_credit_engine",
                  "REDIS_HOST": "redis-cluster.srm.internal"
                }
                """;

        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString(secretJson).build());

        SecretsManagerPropertySource propertySource = new SecretsManagerPropertySource(
                "/secret/srm-credit-engine",
                client
        );
        propertySource.init();

        assertThat(propertySource.getProperty("spring.datasource.username"))
                .isEqualTo("srm_production_user");
        assertThat(propertySource.getProperty("spring.datasource.password"))
                .isEqualTo("SuperSecretPassword123!");
        assertThat(propertySource.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://aurora.srm.internal:5432/srm_credit_engine");
        assertThat(propertySource.getProperty("REDIS_HOST"))
                .isEqualTo("redis-cluster.srm.internal");
    }

    @Test
    @DisplayName("Lanca excecao quando o segredo configurado nao for encontrado na AWS")
    void shouldFailWhenSecretIsNotFoundInAws() {
        SecretsManagerClient client = Mockito.mock(SecretsManagerClient.class);
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("Secret not found").build());

        SecretsManagerPropertySource propertySource = new SecretsManagerPropertySource(
                "/secret/srm-credit-engine-non-existent",
                client
        );

        assertThatThrownBy(propertySource::init)
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Secret not found");
    }
}
