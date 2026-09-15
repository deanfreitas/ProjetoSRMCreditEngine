package br.com.srm.creditengine.domain.settlement;

import br.com.srm.creditengine.domain.DomainException;

import java.util.UUID;

/**
 * Recebivel existe, mas nao tem liquidacao registrada.
 *
 * <p>Erro separado de "recebivel inexistente" de proposito: sao situacoes operacionais
 * diferentes (cadastro errado vs titulo ainda nao liquidado) e quem le o log precisa
 * distinguir.
 */
public class SettlementNotFoundException extends DomainException {

    public SettlementNotFoundException(UUID receivableId) {
        super("Nao existe liquidacao registrada para o recebivel " + receivableId);
    }

    @Override
    public String errorCode() {
        return "SETTLEMENT_NOT_FOUND";
    }
}
