package br.com.srm.creditengine.application.settlement;

import br.com.srm.creditengine.domain.money.Currency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Pedido de liquidacao.
 *
 * @param receivableId       recebivel a liquidar
 * @param settlementCurrency moeda em que o cedente recebe
 * @param idempotencyKey     chave enviada pelo cliente (header {@code Idempotency-Key})
 */
public record SettleReceivableCommand(UUID receivableId, Currency settlementCurrency, String idempotencyKey) {

    public SettleReceivableCommand {
        Objects.requireNonNull(receivableId, "receivableId");
        Objects.requireNonNull(settlementCurrency, "settlementCurrency");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key nao pode ser vazia");
        }
    }

    /**
     * Impressao digital do conteudo do pedido.
     *
     * <p>Permite distinguir retry legitimo (mesma chave, mesmo payload) de chave
     * reaproveitada para outra operacao (mesma chave, payload diferente) - este ultimo e
     * erro do cliente e precisa falhar, nao devolver a liquidacao anterior.
     */
    public String fingerprint() {
        String payload = receivableId + "|" + settlementCurrency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em qualquer JVM; se faltar, e ambiente quebrado
            throw new IllegalStateException("SHA-256 indisponivel nesta JVM", e);
        }
    }
}
