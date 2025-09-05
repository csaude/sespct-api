package mz.org.csaude.sespcet.api.service;

import io.micronaut.cache.annotation.CacheInvalidate;
import io.micronaut.cache.annotation.Cacheable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import mz.org.csaude.sespcet.api.entity.Setting;
import mz.org.csaude.sespcet.api.repository.SettingRepository;
import mz.org.csaude.sespcet.api.util.LifeCycleStatus;
import mz.org.csaude.sespcet.api.util.Utilities;

import java.util.Locale;
import java.util.Optional;

@Singleton
@RequiredArgsConstructor
public class SettingService {

    private final SettingRepository repo;

    @Cacheable("settings")
    protected Optional<String> getRawValueCached(String key) {
        return repo.findByDesignationAndEnabledTrueAndLifeCycleStatusNotEquals(key, LifeCycleStatus.DELETED)
                .map(Setting::getValue)
                .map(String::trim)
                .filter(v -> !v.isEmpty());
    }

    public String get(String key, String def) {
        return getRawValueCached(key).orElse(def);
    }

    public boolean getBoolean(String key, boolean def) {
        String v = get(key, null);
        return Utilities.stringHasValue(v) ? Boolean.parseBoolean(v) : def;
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        try { return Utilities.stringHasValue(v) ? Integer.parseInt(v) : def; }
        catch (NumberFormatException e) { return def; }
    }

    public long getLong(String key, long def) {
        String v = get(key, null);
        try { return Utilities.stringHasValue(v) ? Long.parseLong(v) : def; }
        catch (NumberFormatException e) { return def; }
    }

    public double getDouble(String key, double def) {
        String v = get(key, null);
        try { return Utilities.stringHasValue(v) ? Double.parseDouble(v) : def; }
        catch (NumberFormatException e) { return def; }
    }

    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E def) {
        String v = get(key, null);
        if (!Utilities.stringHasValue(v)) return def;
        try { return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return def; }
    }

    /** Upsert + invalidação da cache dessa key. */
    @CacheInvalidate(cacheNames = "settings") // invalida usando 'key' como parâmetro do método
    public void upsert(String key, String value, String type, String description, boolean enabled, String actor) {
        Setting s = repo.findByDesignation(key).orElseGet(Setting::new);
        s.setDesignation(key);
        s.setValue(value);
        s.setType(type);
        s.setEnabled(enabled);
        s.setDescription(description);
        if (s.getId() == null) {
            s.setCreatedBy(actor != null ? actor : "system");
            s.setLifeCycleStatus(LifeCycleStatus.ACTIVE);
        } else {
            s.setUpdatedBy(actor != null ? actor : "system");
        }
        repo.save(s);
    }

    @CacheInvalidate(cacheNames = "settings", all = true)
    public void evictAll() {}
}
