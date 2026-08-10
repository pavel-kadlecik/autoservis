package cz.palo.autoservis.service;

import cz.palo.autoservis.AbstractIntegrationTest;
import cz.palo.autoservis.client.VehicleRegistryClient;
import cz.palo.autoservis.exception.RegistryUnavailableException;
import cz.palo.autoservis.model.domain.user.Role;
import cz.palo.autoservis.model.domain.user.User;
import cz.palo.autoservis.model.dto.registry.RegistryFetchResult;
import cz.palo.autoservis.model.dto.registry.RegistryLookupParams;
import cz.palo.autoservis.model.dto.registry.RegistryVehicleData;
import cz.palo.autoservis.security.model.domain.AppUserDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full-stack integrační test endpointů registru: HTTP (MockMvc) →
// controller → service → mapper → Testcontainers DB (vč. sync triggeru).
// Mockuje se jen HTTP klient externího registru (@MockitoBean) —
// žádné volání dataovozidlech.cz, žádný API klíč.
//
// @Transactional: MockMvc dispatchuje ve stejném vlákně, takže všechny requesty
// testu sdílejí jeho transakci a na konci se všechno rollbackne.
@AutoConfigureMockMvc
@Transactional
class VehicleRegistryServiceTest extends AbstractIntegrationTest {

    /** Ze seedu V8: BMW řady 3, zákazník 1, aktivní. */
    private static final long VEHICLE_ID = 1L;
    private static final String VEHICLE_VIN = "WBA3A5C50DF595551";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleService vehicleService;

    @MockitoBean
    private VehicleRegistryClient registryClient;

    // Skutečný AppUserDetails principal — @WithMockUser by parametr
    // @AuthenticationPrincipal AppUserDetails resolvoval na null (podrobnosti
    // viz WarehouseImportServiceTest). Uživatel id 1 = admin ze seedu.
    private AppUserDetails admin() {
        return new AppUserDetails(User.builder()
                .id(1L)
                .username("admin")
                .passwordHash("n/a")
                .enabled(true)
                .accountNonExpired(true)
                .accountNonLocked(true)
                .credentialsNonExpired(true)
                .roles(List.of(Role.builder().name("ROLE_ADMIN").build()))
                .build());
    }

    /** Odpověď registru s danou platností STK; ostatní pole odpovídají příkladu z dokumentace API. */
    private RegistryFetchResult registryResult(String vin, String stkValidUntil) {
        RegistryVehicleData data = new RegistryVehicleData(
                vin, "ŠKODA", "FELICIA COMBI", "ČERVENÁ-TMAVÁ",
                "BA 95 B", 1289, "50 / 5000", "AEA", "NE", "NE",
                "1997-03-21T00:00:00", stkValidUntil, "2007-04-02T00:00:00",
                "PROVOZOVANÉ", "AN628498", "UAB648001",
                false, false, 1, 4);
        return new RegistryFetchResult(data,
                "{\"VIN\":\"" + vin + "\",\"NapravyPneuRafky\":\"215/55 R17 94W / 7JX17 ET40 ;/ ;\","
                        + "\"DalsiPole\":\"drženo jen v raw JSONB\"}");
    }

    @Test
    @DisplayName("POST registry-refresh → 200, snapshot uložen, trigger naplnil stkValidUntil v detailu")
    void refresh_storesSnapshotAndTriggerSyncsVehicle() throws Exception {
        given(registryClient.fetch(RegistryLookupParams.ofVin(VEHICLE_VIN)))
                .willReturn(Optional.of(registryResult(VEHICLE_VIN, "2027-06-30T00:00:00")));

        mockMvc.perform(post("/api/v1/vehicles/{id}/registry-refresh", VEHICLE_ID)
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stkValidUntil").value("2027-06-30"))
                .andExpect(jsonPath("$.registryStatus").value("PROVOZOVANÉ"))
                .andExpect(jsonPath("$.lastInspectionDate").value("2007-04-02"))
                .andExpect(jsonPath("$.fetchedAt").exists());

        // Trigger (V62) nasyncoval i kola z raw_response->>NapravyPneuRafky do vozidla.
        mockMvc.perform(get("/api/v1/vehicles/{id}", VEHICLE_ID).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wheels").value("215/55 R17 94W / 7JX17 ET40 ;/ ;"));

        // DB trigger zrcadlí snapshot do vehicles.stk_valid_until.
        mockMvc.perform(get("/api/v1/vehicles/{id}", VEHICLE_ID).with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stkValidUntil").value("2027-06-30"));

        // A seznam snapshotů ho vrací, nejnovější první.
        mockMvc.perform(get("/api/v1/vehicles/{id}/registry-snapshots", VEHICLE_ID)
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stkValidUntil").value("2027-06-30"));
    }

    @Test
    @DisplayName("registr nedostupný → 503 s kódem v errors[]")
    void refresh_registryDown_returns503() throws Exception {
        given(registryClient.fetch(ArgumentMatchers.any()))
                .willThrow(new RegistryUnavailableException("REGISTRY_TIMEOUT",
                        "Registr vozidel neodpovídá."));

        mockMvc.perform(post("/api/v1/vehicles/{id}/registry-refresh", VEHICLE_ID)
                        .with(user(admin())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errors[0].code").value("REGISTRY_TIMEOUT"));
    }

    @Test
    @DisplayName("vozidlo není v registru → 422 VEHICLE_NOT_IN_REGISTRY")
    void refresh_notInRegistry_returns422() throws Exception {
        given(registryClient.fetch(ArgumentMatchers.any())).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/vehicles/{id}/registry-refresh", VEHICLE_ID)
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("VEHICLE_NOT_IN_REGISTRY"));
    }

    @Test
    @DisplayName("vozidlo bez VIN (V90) → 422 VEHICLE_HAS_NO_VIN, klient registru se nevolá")
    void refresh_vehicleWithoutVin_returns422WithoutClientCall() throws Exception {
        var request = new cz.palo.autoservis.model.dto.vehicle.VehicleDto.CreateRequest();
        request.setCustomerId(1L);
        request.setBrand("Husqvarna");
        request.setModel("TC 242T");
        Long machineId = vehicleService.create(request, 1L).getId();

        mockMvc.perform(post("/api/v1/vehicles/{id}/registry-refresh", machineId)
                        .with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("VEHICLE_HAS_NO_VIN"));

        verify(registryClient, never()).fetch(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("GET registry-lookup přes ORV → 200 s namapovanými poli včetně VIN, bez zápisu")
    void lookup_byOrv_mapsFieldsIncludingVin() throws Exception {
        given(registryClient.fetch(new RegistryLookupParams(null, null, "UAB648001")))
                .willReturn(Optional.of(registryResult("TMBEFF654V7529422", "2013-12-06T00:00:00")));

        mockMvc.perform(get("/api/v1/vehicles/registry-lookup")
                        .param("orv", "UAB648001").with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value("TMBEFF654V7529422"))
                .andExpect(jsonPath("$.brand").value("ŠKODA"))
                .andExpect(jsonPath("$.model").value("FELICIA COMBI"))
                .andExpect(jsonPath("$.fuelType").value("PETROL"))
                .andExpect(jsonPath("$.enginePowerKw").value(50))
                .andExpect(jsonPath("$.engineDisplacementCcm").value(1289))
                .andExpect(jsonPath("$.firstRegistrationDate").value("1997-03-21"))
                .andExpect(jsonPath("$.stkValidUntil").value("2013-12-06"));

        // lookup nic neukládá — u žádného vozidla se neobjevil snapshot
        mockMvc.perform(get("/api/v1/vehicles/{id}/registry-snapshots", VEHICLE_ID)
                        .with(user(admin())))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("lookup bez parametrů → 422 MISSING_LOOKUP_PARAM, klient se nevolá")
    void lookup_withoutParams_returns422() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/registry-lookup").with(user(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("MISSING_LOOKUP_PARAM"));

        verify(registryClient, never()).fetch(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("založení vozidla s nedostupným registrem → přesto 201 (best-effort)")
    void createVehicle_registryDown_stillCreated() throws Exception {
        given(registryClient.fetch(ArgumentMatchers.any()))
                .willThrow(new RegistryUnavailableException("REGISTRY_TIMEOUT",
                        "Registr vozidel neodpovídá."));

        mockMvc.perform(post("/api/v1/vehicles")
                        .contentType("application/json")
                        .content("""
                                { "customerId": 1, "vin": "TMBTEST0000000001",
                                  "brand": "Škoda", "model": "Octavia", "fuelType": "PETROL" }
                                """)
                        .with(user(admin())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vin").value("TMBTEST0000000001"));
    }

    @Test
    @DisplayName("filtr stkExpiring vrací jen vozidla s STK do 30 dnů / propadlou")
    void listFilter_stkExpiring_returnsOnlyExpiring() throws Exception {
        // Vozidlo 1 → STK daleko v budoucnu; vozidlo 2 → už propadlá.
        given(registryClient.fetch(RegistryLookupParams.ofVin(VEHICLE_VIN)))
                .willReturn(Optional.of(registryResult(VEHICLE_VIN, "2099-01-01T00:00:00")));
        given(registryClient.fetch(RegistryLookupParams.ofVin("TMBKG6NW2L7234565")))
                .willReturn(Optional.of(registryResult("TMBKG6NW2L7234565", "2020-01-01T00:00:00")));

        mockMvc.perform(post("/api/v1/vehicles/1/registry-refresh").with(user(admin())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/vehicles/2/registry-refresh").with(user(admin())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/vehicles")
                        .param("stkExpiring", "true").param("activeOnly", "true")
                        .with(user(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == 2)]").exists())
                .andExpect(jsonPath("$.content[?(@.id == 1)]").doesNotExist());
    }
}
