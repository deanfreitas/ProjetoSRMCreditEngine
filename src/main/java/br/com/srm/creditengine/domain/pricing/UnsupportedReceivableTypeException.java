package br.com.srm.creditengine.domain.pricing;

import br.com.srm.creditengine.domain.DomainException;

/**
 * Tipo de recebivel sem strategy registrada. Falha explicita em vez de fallback para
 * um spread "padrao": no Anexo A, qualquer tipo diferente de DUPLICATA recebia 2,5%
 * silenciosamente, o que precifica errado um ativo desconhecido.
 */
public class UnsupportedReceivableTypeException extends DomainException {

    public UnsupportedReceivableTypeException(ReceivableType type) {
        super("Nenhuma strategy de precificacao registrada para o tipo " + type);
    }

    @Override
    public String errorCode() {
        return "UNSUPPORTED_RECEIVABLE_TYPE";
    }
}
