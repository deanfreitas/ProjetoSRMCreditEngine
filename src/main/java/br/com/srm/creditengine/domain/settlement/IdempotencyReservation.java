package br.com.srm.creditengine.domain.settlement;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resultado da tentativa de reservar uma {@code Idempotency-Key} no guarda de idempotencia.
 *
 * <p>O token existe para que a liberacao da reserva seja segura sob concorrencia: quem
 * compensa e quem reservou, nunca a requisicao vizinha que pegou a mesma chave depois de o
 * TTL expirar.
 *
 * @param status       o que o guarda respondeu
 * @param idempotencyKey chave enviada pelo cliente
 * @param fingerprint  impressao digital do pedido corrente
 * @param token        identificador desta reserva; {@code null} quando nada foi reservado
 * @param settlementId liquidacao ja concluida para esta chave, quando conhecida
 */
public record IdempotencyReservation(Status status,
                                     String idempotencyKey,
                                     String fingerprint,
                                     String token,
                                     UUID settlementId) {

    /** Veredito do guarda de idempotencia. */
    public enum Status {

        /** Reserva obtida: esta requisicao e a dona do trabalho e pode prosseguir. */
        ACQUIRED,

        /** Mesma chave e mesmo payload em execucao agora, por outra requisicao. */
        IN_PROGRESS,

        /** Chave ja produziu liquidacao: o pedido e um retry e deve ser respondido com ela. */
        COMPLETED,

        /** Mesma chave com payload diferente: erro do cliente. */
        CONFLICT,

        /**
         * Guarda indisponivel (ou desligado). Nao e erro para o cliente: a requisicao segue
         * pelo caminho do PostgreSQL, que continua correto - apenas mais caro.
         */
        UNAVAILABLE
    }

    public IdempotencyReservation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }

    public static IdempotencyReservation acquired(String idempotencyKey, String fingerprint, String token) {
        return new IdempotencyReservation(Status.ACQUIRED, idempotencyKey, fingerprint,
                Objects.requireNonNull(token, "token"), null);
    }

    public static IdempotencyReservation inProgress(String idempotencyKey, String fingerprint) {
        return new IdempotencyReservation(Status.IN_PROGRESS, idempotencyKey, fingerprint, null, null);
    }

    public static IdempotencyReservation completed(String idempotencyKey, String fingerprint, UUID settlementId) {
        return new IdempotencyReservation(Status.COMPLETED, idempotencyKey, fingerprint, null, settlementId);
    }

    public static IdempotencyReservation conflict(String idempotencyKey, String fingerprint) {
        return new IdempotencyReservation(Status.CONFLICT, idempotencyKey, fingerprint, null, null);
    }

    public static IdempotencyReservation unavailable(String idempotencyKey, String fingerprint) {
        return new IdempotencyReservation(Status.UNAVAILABLE, idempotencyKey, fingerprint, null, null);
    }

    /**
     * {@code true} quando a unicidade da chave ja esta garantida por esta reserva e a
     * consulta de idempotencia no banco pode ser dispensada.
     */
    public boolean guarded() {
        return status == Status.ACQUIRED;
    }

    /** {@code true} quando existe reserva a ser compensada em caso de falha. */
    public boolean compensable() {
        return token != null;
    }

    public Optional<UUID> optionalSettlementId() {
        return Optional.ofNullable(settlementId);
    }
}
