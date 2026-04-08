package com.wxut.agentplaza.service;

import com.wxut.agentplaza.entity.SysFile;
import org.springframework.web.multipart.MultipartFile;

public interface SysFileService {
    SysFile upload(MultipartFile file, Long userId);
    SysFile getById(Long id);
    void delete(Long id);
}
