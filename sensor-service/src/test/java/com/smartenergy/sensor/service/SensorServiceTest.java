package com.smartenergy.sensor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartenergy.contracts.error.ApiException;
import com.smartenergy.contracts.error.ErrorCode;
import com.smartenergy.contracts.sensor.SensorRequest;
import com.smartenergy.contracts.sensor.SensorResponse;
import com.smartenergy.sensor.model.SensorDocument;
import com.smartenergy.sensor.repository.SensorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests unitaires (sans Spring ni base) de la logique du registre de capteurs. */
class SensorServiceTest {

    private final SensorRepository repository = mock(SensorRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final SensorService service = new SensorService(repository, mongo);

    private static SensorRequest request(String id, Long version) {
        return new SensorRequest(id, "Compteur", "Batiment A", 1, 8.0, null, List.of("critical"), version);
    }

    @Test
    void create_renvoie_le_capteur_actif_par_defaut() {
        when(repository.existsBySensorId("SENSOR-001")).thenReturn(false);
        when(repository.save(any(SensorDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        SensorResponse created = service.create(request("SENSOR-001", null));

        assertThat(created.sensorId()).isEqualTo("SENSOR-001");
        assertThat(created.active()).isTrue();
        assertThat(created.maxPowerKw()).isEqualTo(8.0);
    }

    @Test
    void create_refuse_un_doublon() {
        when(repository.existsBySensorId("SENSOR-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("SENSOR-001", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SENSOR_ALREADY_EXISTS));
    }

    @Test
    void update_refuse_un_sensorId_different_de_l_url() {
        assertThatThrownBy(() -> service.update("SENSOR-001", request("SENSOR-002", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    @Test
    void update_inconnu_donne_SENSOR_NOT_FOUND() {
        when(repository.findBySensorId("SENSOR-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("SENSOR-404", request("SENSOR-404", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SENSOR_NOT_FOUND));
    }

    @Test
    void list_refuse_une_taille_hors_limites() {
        assertThatThrownBy(() -> service.list(0, 500, null, null))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_PARAMETER));
    }

    @Test
    void update_avec_version_perimee_donne_VERSION_CONFLICT_et_une_version_courante_reussit() {
        // le champ @Version n'a pas de setter : on simule un document persiste (version 5)
        SensorDocument persiste = new SensorDocument("SENSOR-001");
        ReflectionTestUtils.setField(persiste, "version", 5L);
        when(repository.findBySensorId("SENSOR-001")).thenReturn(Optional.of(persiste));
        when(repository.save(any(SensorDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        // version perimee (4 < 5) -> 409 VERSION_CONFLICT, aucune ecriture
        assertThatThrownBy(() -> service.update("SENSOR-001", request("SENSOR-001", 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(repository, never()).save(any(SensorDocument.class));

        // version courante -> mise a jour sauvegardee
        SensorResponse miseAJour = service.update("SENSOR-001", request("SENSOR-001", 5L));
        assertThat(miseAJour.version()).isEqualTo(5L);
        verify(repository).save(any(SensorDocument.class));
    }

    @Test
    void create_avec_duplicateKeyException_sur_l_index_unique_donne_SENSOR_ALREADY_EXISTS() {
        // la course : le controle existsBySensorId passe (false) mais l'index unique
        // uk_sensorId tranche entre deux creations simultanees -> DuplicateKeyException
        when(repository.existsBySensorId("SENSOR-001")).thenReturn(false);
        when(repository.save(any(SensorDocument.class))).thenThrow(new DuplicateKeyException("uk_sensorId"));

        assertThatThrownBy(() -> service.create(request("SENSOR-001", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.SENSOR_ALREADY_EXISTS));
    }
}
