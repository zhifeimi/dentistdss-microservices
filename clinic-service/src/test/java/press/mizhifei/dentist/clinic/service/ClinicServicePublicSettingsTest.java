package press.mizhifei.dentist.clinic.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import press.mizhifei.dentist.clinic.client.AppointmentServiceClient;
import press.mizhifei.dentist.clinic.client.PatientServiceClient;
import press.mizhifei.dentist.clinic.client.UserProfileServiceClient;
import press.mizhifei.dentist.clinic.directory.ClinicSlugService;
import press.mizhifei.dentist.clinic.dto.ClinicResponse;
import press.mizhifei.dentist.clinic.dto.ClinicUpdateRequest;
import press.mizhifei.dentist.clinic.dto.OperatingHoursDto;
import press.mizhifei.dentist.clinic.model.Clinic;
import press.mizhifei.dentist.clinic.model.ClinicOperatingHours;
import press.mizhifei.dentist.clinic.repository.ClinicOperatingHoursRepository;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClinicServicePublicSettingsTest {

    private static final LocalDateTime LAST_EDIT = LocalDateTime.of(2026, 1, 1, 0, 0);

    private ClinicRepository clinicRepository;
    private ClinicOperatingHoursRepository hoursRepository;
    private ClinicSlugService slugService;
    private ClinicService service;
    private Clinic clinic;
    private ClinicOperatingHours monday;
    private ClinicOperatingHours tuesday;

    @BeforeEach
    void setUp() {
        clinicRepository = mock(ClinicRepository.class);
        hoursRepository = mock(ClinicOperatingHoursRepository.class);
        slugService = mock(ClinicSlugService.class);
        service = new ClinicService(clinicRepository, mock(AppointmentServiceClient.class),
                mock(PatientServiceClient.class), mock(UserProfileServiceClient.class),
                hoursRepository, slugService);

        clinic = Clinic.builder().id(7L).name("Smile Dental").city("Parramatta").state("NSW")
                .approved(true).enabled(true).slug("smile-dental-parramatta").updatedAt(LAST_EDIT).build();
        monday = hours(10L, 1, "08:30", "17:30", true);
        tuesday = hours(11L, 2, "08:30", "17:30", true);
        when(clinicRepository.findById(7L)).thenReturn(Optional.of(clinic));
        when(clinicRepository.save(any(Clinic.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(hoursRepository.findByClinicIdOrderByDayOfWeekAsc(7L))
                .thenReturn(new ArrayList<>(List.of(monday, tuesday)));
    }

    @Test
    void hoursAreReplacedInPlacePerDay() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(
                new OperatingHoursDto(1, "09:00", "18:00", true),
                new OperatingHoursDto(3, "08:00", "12:00", true)));

        service.updateClinic(7L, request);

        // Monday is updated in place (same row), Wednesday inserted, Tuesday dropped.
        assertThat(monday.getId()).isEqualTo(10L);
        assertThat(monday.getOpenTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(monday.getCloseTime()).isEqualTo(LocalTime.of(18, 0));
        ArgumentCaptor<ClinicOperatingHours> inserted = ArgumentCaptor.forClass(ClinicOperatingHours.class);
        verify(hoursRepository).save(inserted.capture());
        assertThat(inserted.getValue().getDayOfWeek()).isEqualTo(3);
        assertThat(inserted.getValue().getClinicId()).isEqualTo(7L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ClinicOperatingHours>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(hoursRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(tuesday);
        assertThat(clinic.getUpdatedAt()).isAfter(LAST_EDIT);
    }

    @Test
    void identicalHoursChangeNothing() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setOperatingHours(List.of(
                new OperatingHoursDto(1, "08:30", "17:30", true),
                new OperatingHoursDto(2, "08:30", "17:30", true)));

        service.updateClinic(7L, request);

        verify(hoursRepository, never()).save(any());
        verify(hoursRepository, never()).deleteAll(anyIterable());
        assertThat(clinic.getUpdatedAt()).isEqualTo(LAST_EDIT);
    }

    @Test
    void omittedHoursAreLeftAlone() {
        service.updateClinic(7L, new ClinicUpdateRequest());

        verify(hoursRepository, never()).save(any());
        verify(hoursRepository, never()).deleteAll(anyIterable());
    }

    @Test
    void publicFieldsAreSavedAndReturned() {
        ClinicUpdateRequest request = new ClinicUpdateRequest();
        request.setDescription("  Family clinic near the station.  ");
        request.setWebsite("https://smile.example");
        request.setLatitude(-33.815);
        request.setLongitude(151.0011);
        request.setListedPublicly(true);

        ClinicResponse response = service.updateClinic(7L, request);

        assertThat(clinic.getDescription()).isEqualTo("Family clinic near the station.");
        assertThat(clinic.getListedPublicly()).isTrue();
        assertThat(response.getListedPublicly()).isTrue();
        assertThat(response.getSlug()).isEqualTo("smile-dental-parramatta");
        assertThat(response.getLatitude()).isEqualTo(-33.815);
        assertThat(response.getOperatingHours()).extracting(OperatingHoursDto::dayOfWeek).containsExactly(1, 2);

        ClinicUpdateRequest clear = new ClinicUpdateRequest();
        clear.setDescription(" ");
        clear.setWebsite("");
        service.updateClinic(7L, clear);
        assertThat(clinic.getDescription()).isNull();
        assertThat(clinic.getWebsite()).isNull();
    }

    @Test
    void slugIsRefreshedOnlyForApprovedActiveClinics() {
        ClinicUpdateRequest rename = new ClinicUpdateRequest();
        rename.setName("Bright Smile Dental");

        service.updateClinic(7L, rename);
        verify(slugService).refreshSlug(eq(clinic), any());

        Clinic pending = Clinic.builder().id(8L).name("Pending").approved(false).enabled(false).build();
        when(clinicRepository.findById(8L)).thenReturn(Optional.of(pending));
        when(hoursRepository.findByClinicIdOrderByDayOfWeekAsc(8L)).thenReturn(List.of());
        service.updateClinic(8L, rename);
        verify(slugService, never()).refreshSlug(eq(pending), any());
    }

    private static ClinicOperatingHours hours(Long id, int day, String open, String close, boolean isOpen) {
        return ClinicOperatingHours.builder().id(id).clinicId(7L).dayOfWeek(day)
                .openTime(LocalTime.parse(open)).closeTime(LocalTime.parse(close)).isOpen(isOpen).build();
    }
}
