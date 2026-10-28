package com.agentscopea2a.v2.skillManager.mapper;
import com.agentscopea2a.v2.skillManager.entity.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper
public interface LongTaskConfigMapper {
 List<LongTaskType> selectTypes(@Param("enabled") Boolean enabled);
 LongTaskType selectTypeById(@Param("id") Long id);
 LongTaskType selectTypeByName(@Param("name") String name);
 void insertType(LongTaskType type);
 void updateType(LongTaskType type);
 int updateTypeEnabled(@Param("id") Long id,@Param("enabled") Boolean enabled);
 int deleteType(@Param("id") Long id);
 long countFlowsByTypeId(@Param("typeId") Long typeId);
 List<LongTaskTag> selectTags();
 LongTaskTag selectTagByName(@Param("name") String name);
 void insertTag(LongTaskTag tag);
 List<LongTaskTag> selectTagsByFlowId(@Param("flowId") Long flowId);
 void deleteFlowTags(@Param("flowId") Long flowId);
 void insertFlowTag(@Param("flowId") Long flowId,@Param("tagId") Long tagId);
}
