package br.com.srm.creditengine.domain.pricing;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolve a strategy pelo tipo de recebivel.
 *
 * <p>Substitui o {@code if/else} com spread hardcoded do Anexo A: registrar um tipo novo
 * e adicionar uma implementacao, nao editar um switch espalhado pelo codigo.
 * Duas strategies para o mesmo tipo e erro de configuracao e falha na inicializacao.
 */
public class PricingStrategyRegistry {

    private final Map<ReceivableType, PricingStrategy> strategiesByType = new EnumMap<>(ReceivableType.class);

    public PricingStrategyRegistry(Collection<? extends PricingStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies");
        for (PricingStrategy strategy : strategies) {
            PricingStrategy previous = strategiesByType.put(strategy.receivableType(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Mais de uma strategy registrada para " + strategy.receivableType()
                                + ": " + previous.getClass().getName() + " e " + strategy.getClass().getName());
            }
        }
    }

    public static PricingStrategyRegistry of(PricingStrategy... strategies) {
        return new PricingStrategyRegistry(List.of(strategies));
    }

    public PricingStrategy strategyFor(ReceivableType type) {
        PricingStrategy strategy = strategiesByType.get(type);
        if (strategy == null) {
            throw new UnsupportedReceivableTypeException(type);
        }
        return strategy;
    }

    public Collection<PricingStrategy> registered() {
        return List.copyOf(strategiesByType.values());
    }
}
