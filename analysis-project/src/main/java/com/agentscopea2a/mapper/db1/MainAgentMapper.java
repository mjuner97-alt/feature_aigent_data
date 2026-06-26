package com.agentscopea2a.mapper.db1;

import com.agentscopea2a.dto.QuestionAnswerDto;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MainAgentMapper {

    void insertAnswerTable(QuestionAnswerDto questionAnswerDTO);

    void insertToQualityAnalysisConversationAnswer(QuestionAnswerDto questionAnswerDTO);

    QuestionAnswerDto selectAnswerRecordByTaskId(String conversationId);
}

