package com.test.credit.score.model.domain;

import com.test.credit.score.scoring.domain.ModelRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelRegistrationRepository extends JpaRepository<ModelRegistration, String> {

    List<ModelRegistration> findByRoleIn(List<ModelRole> roles);

    Optional<ModelRegistration> findTopByRole(ModelRole role);
}
