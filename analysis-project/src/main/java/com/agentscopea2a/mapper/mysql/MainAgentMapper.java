package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.dto.QuestionAnswerDto;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MainAgentMapper {

    void insertAiUserTable(QuestionAnswerDto answerDto);

    void insertAnswerTable(QuestionAnswerDto questionAnswerDTO);

    void insertToQualityAnalysisConversationAnswer(QuestionAnswerDto questionAnswerDTO);

    QuestionAnswerDto selectAnswerRecordByTaskId(String conversationId);
}

