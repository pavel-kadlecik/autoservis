package cz.palo.autoservis.model.dto.registry;

/**
 * Výsledek úspěšného dotazu do registru.
 *
 * @param data    namapovaná podmnožina objektu {@code Data} z registru
 * @param rawJson kompletní objekt {@code Data} jako JSON text (~70 polí) —
 *                ukládá se do {@code registry_snapshots.raw_response} (JSONB),
 *                aby budoucí potřeby nevyžadovaly nová volání API (limit 27 req/min)
 */
public record RegistryFetchResult(RegistryVehicleData data, String rawJson) {
}
