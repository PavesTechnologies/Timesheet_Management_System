package com.intranet.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDTO {
    private Long id;
    private String employee_id;
    private String user_uuid;
    private String name;
    private String email;
    private List<String> roles;
    private List<String> permissions;
}
