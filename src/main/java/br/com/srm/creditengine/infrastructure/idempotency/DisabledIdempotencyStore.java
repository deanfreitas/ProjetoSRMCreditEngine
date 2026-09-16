package br.com.srm.creditengine.infrastructure.idempotency;

import br.com.srm.creditengine.domain.settlement.IdempotencyReservation;
import br.com.srm.creditengine.domain.settlement.IdempotencyStore;

import java.util.UUID;

/**
 * Guarda desligado: responde sempre {@code UNAVAILABLE} e nao guarda nada.
 *
 * <p>Nao e stub de teste - e o modo de operacao sem Redis. Com este bean no lugar, a
 * liquidacao volta a decidir idempotencia pelo {@code SELECT} em {@code settlements} dentro
 * da propria transacao, exatamente como antes do Redis entrar. E por isso que a reserva
 * pode ser tratada como otimizacao: existe um caminho correto sem ela.
 */
public class DisabledIdempotencyStore implements IdempotencyStore {

    @Override
    public IdempotencyReservation reserve(String idempotencyKey, String fingerprint) {
        return IdempotencyReservation.unavailable(idempotencyKey, fingerprint);
    }

    @Override
    public void complete(IdempotencyReservation reservation, UUID settlementId) {
        // Nada a publicar: sem guarda, a chave vive apenas na liquidacao gravada.
    }

    @Override
    public void release(IdempotencyReservation reservation) {
        // Nada a compensar: nenhuma reserva foi feita.
    }
}
