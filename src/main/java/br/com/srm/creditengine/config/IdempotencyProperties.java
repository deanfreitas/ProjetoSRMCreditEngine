package br.com.srm.creditengine.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Parametros do guarda de idempotencia em Redis. */
@Validated
@ConfigurationProperties(prefix = "credit-engine.idempotency")
public class IdempotencyProperties {

    /**
     * Liga o guarda em Redis. Desligado, a liquidacao decide idempotencia pelo
     * {@code SELECT} em {@code settlements} dentro da propria transacao - o caminho anterior
     * ao Redis, que continua correto.
     */
    private boolean enabled = true;

    /** Prefixo das chaves. Namespace explicito para conviver com outros usos do mesmo Redis. */
    @NotBlank
    private String keyPrefix = "srm:idempotency:settlement:";

    /**
     * Teto de vida da reserva {@code PROCESSING}.
     *
     * <p>Curto de proposito: se o processo morrer entre reservar e commitar, e por este
     * prazo que um retry legitimo fica recebendo "em processamento". Precisa ser maior que a
     * pior duracao plausivel da liquidacao (cotacao externa com timeout e retry inclusos) e
     * menor que a paciencia do cliente.
     */
    @NotNull
    private Duration inProgressTtl = Duration.ofSeconds(30);

    /**
     * Janela em que o retry de uma chave concluida e respondido sem tocar no banco.
     *
     * <p>Expirar aqui nao reabre risco de pagamento duplo: apos o TTL, o retry cai no
     * caminho do PostgreSQL e e barrado por {@code uk_settlements_idempotency_key}.
     */
    @NotNull
    private Duration completedTtl = Duration.ofHours(24);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Duration getInProgressTtl() {
        return inProgressTtl;
    }

    public void setInProgressTtl(Duration inProgressTtl) {
        this.inProgressTtl = inProgressTtl;
    }

    public Duration getCompletedTtl() {
        return completedTtl;
    }

    public void setCompletedTtl(Duration completedTtl) {
        this.completedTtl = completedTtl;
    }
}
