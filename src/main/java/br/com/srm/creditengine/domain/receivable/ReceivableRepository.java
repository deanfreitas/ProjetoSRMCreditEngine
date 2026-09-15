package br.com.srm.creditengine.domain.receivable;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistencia de recebiveis. A interface vive no dominio e a implementacao na
 * infraestrutura: o servico de liquidacao depende de contrato, nao de JPA.
 */
public interface ReceivableRepository {

    Optional<Receivable> findById(UUID id);

    Receivable save(Receivable receivable);

    /**
     * Grava a transicao para liquidado usando <b>optimistic locking</b> sobre a versao que
     * veio em {@code receivable}.
     *
     * @throws ConcurrentSettlementException se outra transacao alterou o recebivel no
     *                                       intervalo (duplo clique, retry paralelo)
     */
    void settle(Receivable receivable);
}
