package br.com.srm.creditengine.domain.settlement;

import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia do registro de liquidacao. Nao expoe update nem delete. */
public interface SettlementRepository {

    Settlement save(Settlement settlement);

    Optional<Settlement> findByIdempotencyKey(String idempotencyKey);

    Optional<Settlement> findByReceivableId(UUID receivableId);
}
