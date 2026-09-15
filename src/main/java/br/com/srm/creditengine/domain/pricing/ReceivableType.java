package br.com.srm.creditengine.domain.pricing;

/**
 * Tipos de recebivel aceitos pelo fundo.
 *
 * <p>O enum <b>nao</b> carrega o spread: a regra de precificacao vive na
 * {@link PricingStrategy} correspondente. Assim um tipo novo com formula diferente
 * (carencia, desconto simples, taxa por faixa de prazo) entra sem alterar o motor.
 */
public enum ReceivableType {

    DUPLICATA_MERCANTIL("Duplicata Mercantil"),
    CHEQUE_PRE_DATADO("Cheque Pre-datado");

    private final String description;

    ReceivableType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
