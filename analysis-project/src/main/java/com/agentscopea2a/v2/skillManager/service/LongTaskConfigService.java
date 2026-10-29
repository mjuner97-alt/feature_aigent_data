package com.agentscopea2a.v2.skillManager.service;
import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.LongTaskConfigMapper;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
public class LongTaskConfigService {
 private final LongTaskConfigMapper mapper;
 public LongTaskConfigService(LongTaskConfigMapper mapper){this.mapper=mapper;}
 public List<LongTaskTypeResponse> listTypes(Boolean enabled){return mapper.selectTypes(enabled).stream().map(this::response).toList();}
 @Transactional public LongTaskTypeResponse createType(LongTaskTypeRequest req,String user){var n=normalize(req.name()); ensureUniqueSort(null,validSort(req.sortOrder())); if(mapper.selectTypeByName(n)!=null) throw new IllegalArgumentException("LongTaskTypeNameAlreadyExists"); var t=new LongTaskType(null,n,req.description(),validSort(req.sortOrder()),req.enabled()==null||req.enabled(),null,null); mapper.insertType(t); return response(t);}
 @Transactional public LongTaskTypeResponse updateType(Long id,LongTaskTypeRequest req,String user){var n=normalize(req.name()); var old=mapper.selectTypeById(id); if(old==null) throw new IllegalArgumentException("LongTaskTypeNotFound"); ensureUniqueSort(id,validSort(req.sortOrder())); var same=mapper.selectTypeByName(n); if(same!=null&&!id.equals(same.getId())) throw new IllegalArgumentException("LongTaskTypeNameAlreadyExists"); var t=new LongTaskType(id,n,req.description(),validSort(req.sortOrder()),req.enabled()==null?old.getEnabled():req.enabled(),old.getCreatedAt(),null); mapper.updateType(t); return response(t);}
 @Transactional public void deleteType(Long id,String user){if(mapper.countFlowsByTypeId(id)>0) throw new IllegalStateException("LongTaskTypeInUse"); if(mapper.deleteType(id)==0) throw new IllegalArgumentException("LongTaskTypeNotFound");}
 private void ensureUniqueSort(Long currentId,int sort){if(mapper.selectTypes(null).stream().anyMatch(t->sort==t.getSortOrder()&&(currentId==null||!currentId.equals(t.getId())))) throw new IllegalArgumentException("LongTaskTypeSortOrderAlreadyExists");}
 private static String normalize(String n){if(n==null||n.trim().isEmpty()) throw new IllegalArgumentException("LongTaskTypeNameRequired");return n.trim();}
 private static int validSort(Integer n){if(n==null||n<0) throw new IllegalArgumentException("LongTaskTypeSortOrderInvalid");return n;}
 private LongTaskTypeResponse response(LongTaskType t){return new LongTaskTypeResponse(t.getId(),t.getName(),t.getDescription(),t.getSortOrder(),t.getEnabled(),t.getCreatedAt(),t.getUpdatedAt());}
}





