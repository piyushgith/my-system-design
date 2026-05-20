package com.test.credit.score.model.application;

import com.test.credit.score.model.domain.ModelRegistration;
import com.test.credit.score.model.domain.ModelRegistrationRepository;
import com.test.credit.score.scoring.domain.ModelRole;
import com.test.credit.score.scoring.domain.ProductType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelRegistryService {

    private final ModelRegistrationRepository repo;

    // role → active ModelRegistration (in-memory cache, refreshed on startup)
    private final Map<ModelRole, ModelRegistration> activeModels = new ConcurrentHashMap<>();

    @PostConstruct
    void loadActiveModels() {
        List<ModelRegistration> models = repo.findByRoleIn(List.of(ModelRole.CHAMPION, ModelRole.CHALLENGER));
        models.forEach(m -> activeModels.put(m.getRole(), m));
        log.info("Loaded {} active model(s): {}", models.size(),
                models.stream().map(ModelRegistration::getModelVersion).toList());
    }

    public ModelRegistration getChampion(ProductType productType) {
        ModelRegistration champion = activeModels.get(ModelRole.CHAMPION);
        if (champion == null || !champion.supportsProduct(productType.name())) {
            throw new NoSuchElementException("No champion model for product: " + productType);
        }
        return champion;
    }

    public ModelRegistration getChallenger(ProductType productType) {
        ModelRegistration challenger = activeModels.get(ModelRole.CHALLENGER);
        if (challenger == null || !challenger.supportsProduct(productType.name())) {
            // Fall back to champion if no challenger configured
            return getChampion(productType);
        }
        return challenger;
    }

    public List<ModelRegistration> listAll() {
        return repo.findAll();
    }

    /** Hot-reload: called after a model.promoted event (V1 feature). */
    public void refresh() {
        activeModels.clear();
        loadActiveModels();
    }
}
