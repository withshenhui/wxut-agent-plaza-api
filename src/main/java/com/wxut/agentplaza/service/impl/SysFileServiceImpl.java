package com.wxut.agentplaza.service.impl;

import com.wxut.agentplaza.entity.SysFile;
import com.wxut.agentplaza.exception.BusinessException;
import com.wxut.agentplaza.mapper.SysFileMapper;
import com.wxut.agentplaza.service.SysFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SysFileServiceImpl implements SysFileService {

    private final SysFileMapper fileMapper;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public SysFile upload(MultipartFile file, Long userId) {
        if (file.isEmpty()) throw new BusinessException("文件不能为空");
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();
        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".")) : "";
        String storedName = UUID.randomUUID().toString() + ext;
        String filePath = uploadDir + "/" + storedName;
        try {
            file.transferTo(new File(filePath));
        } catch (IOException e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
        SysFile sysFile = new SysFile();
        sysFile.setOriginalName(originalName);
        sysFile.setStoredName(storedName);
        sysFile.setFilePath(filePath);
        sysFile.setFileSize(file.getSize());
        sysFile.setMimeType(file.getContentType());
        sysFile.setUploadUserId(userId);
        fileMapper.insert(sysFile);
        return sysFile;
    }

    @Override
    public SysFile getById(Long id) {
        SysFile f = fileMapper.selectById(id);
        if (f == null) throw new BusinessException("文件不存在");
        return f;
    }

    @Override
    public void delete(Long id) {
        SysFile f = getById(id);
        new File(f.getFilePath()).delete();
        fileMapper.deleteById(id);
    }
}
