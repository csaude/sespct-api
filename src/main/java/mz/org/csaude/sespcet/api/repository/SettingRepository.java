package mz.org.csaude.sespcet.api.repository;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jpa.repository.JpaRepository;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Slice;
import mz.org.csaude.sespcet.api.entity.Setting;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;

import java.util.Optional;

@Repository
public interface SettingRepository extends JpaRepository<Setting, Long> {
    Optional<Setting> findByDesignation(String designation);

    Page<Setting> findByDesignationIlike(String designation, Pageable pageable);

    Optional<Setting> findByDesignationAndEnabledTrueAndLifeCycleStatusNotEquals(
            String designation, LifeCycleStatus lifeCycleStatus);
}
