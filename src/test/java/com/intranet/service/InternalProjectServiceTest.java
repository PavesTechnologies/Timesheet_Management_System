package com.intranet.service;

import com.intranet.entity.InternalProject;
import com.intranet.repository.InternalProjectRepo;
import com.intranet.repository.TimeSheetEntryRepo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalProjectServiceTest {

    @Mock
    private InternalProjectRepo repository;

    @Mock
    private TimeSheetEntryRepo timeSheetEntryRepo;

    @InjectMocks
    private InternalProjectService service;

    private InternalProject project;

    @BeforeEach
    void setUp() {
        project = new InternalProject();
        project.setId(1L);
        project.setProjectId(-1);
        project.setTaskId(-1);
        project.setTaskName("Testing Task");
        project.setBillable(false);
    }

    // CREATE
    @Test
    void shouldCreateInternalTaskSuccessfully() {

        when(repository.findTopByOrderByTaskIdAsc())
                .thenReturn(Optional.of(project));

        when(repository.existsByTaskId(anyInt()))
                .thenReturn(false);

        when(repository.save(any()))
                .thenReturn(project);

        InternalProject result = service.createInternalTask("Testing Task");

        assertNotNull(result);
        assertEquals("Testing Task", result.getTaskName());
        verify(repository, times(1)).save(any());
    }

    @Test
    void shouldThrowExceptionWhenTaskNameEmpty() {

        Exception exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.createInternalTask("")
        );

        assertEquals("Task name cannot be empty", exception.getMessage());
    }

    // READ
    @Test
    void shouldReturnAllProjects() {

        when(repository.findAll()).thenReturn(List.of(project));

        List<InternalProject> result = service.getAllProjects();

        assertEquals(1, result.size());
        verify(repository, times(1)).findAll();
    }

    // UPDATE
    @Test
    void shouldUpdateInternalTask() {

        when(repository.findById(1L)).thenReturn(Optional.of(project));
        when(repository.save(any())).thenReturn(project);

        InternalProject updated = service.updateInternalTask(1L, "Updated Task");

        assertEquals("Updated Task", updated.getTaskName());
        verify(repository).save(any());
    }

    @Test
    void shouldThrowExceptionIfProjectNotFound() {

        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class,
                () -> service.updateInternalTask(1L, "Test"));
    }

    // DELETE
    @Test
    void shouldDeleteProjectSuccessfully() {

        when(repository.findById(1L)).thenReturn(Optional.of(project));
        when(timeSheetEntryRepo.existsByProjectIdAndTaskId(-1, -1))
                .thenReturn(false);

        String result = service.deleteProject(1L);

        assertEquals("Internal Project deleted successfully", result);

        verify(repository).deleteById(1L);
    }

    @Test
    void shouldThrowExceptionIfUsedInTimesheet() {

        when(repository.findById(1L)).thenReturn(Optional.of(project));
        when(timeSheetEntryRepo.existsByProjectIdAndTaskId(-1, -1))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteProject(1L));
    }
}