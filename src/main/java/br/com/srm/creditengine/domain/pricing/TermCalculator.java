package br.com.srm.creditengine.domain.pricing;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Converte datas em prazo de meses inteiros.
 *
 * <p>Ambiguidade do enunciado resolvida no SPEC.md: o prazo da formula esta em
 * <b>meses inteiros</b>, na convencao de mes comercial de 30 dias (30/360), e fracao de
 * mes e arredondada <b>para cima</b>. Justificativa: o fundo fica exposto ao risco durante
 * todo o mes iniciado, e arredondar para baixo faria o titulo ser comprado mais caro do que
 * o risco assumido. E premissa de negocio, nao detalhe tecnico - por isso fica explicita
 * aqui e no SPEC, e nao escondida dentro do motor.
 *
 * <p>Os golden cases informam o prazo em meses diretamente e nao passam por esta classe.
 */
public final class TermCalculator {

    private static final int COMMERCIAL_MONTH_DAYS = 30;

    private TermCalculator() {
    }

    public static int termInMonths(LocalDate operationDate, LocalDate dueDate) {
        if (operationDate == null || dueDate == null) {
            throw new InvalidPricingInputException("Data da operacao e vencimento sao obrigatorios");
        }
        if (dueDate.isBefore(operationDate)) {
            throw new InvalidPricingInputException(
                    "Vencimento %s anterior a data da operacao %s".formatted(dueDate, operationDate));
        }
        long days = ChronoUnit.DAYS.between(operationDate, dueDate);
        return (int) Math.ceilDiv(days, COMMERCIAL_MONTH_DAYS);
    }
}
