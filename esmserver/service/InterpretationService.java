package io.github.rladmstj.esmserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rladmstj.esmserver.dto.InterpretationResponse;
import io.github.rladmstj.esmserver.dto.SpeechInterpretationRequest;
import io.github.rladmstj.esmserver.dto.openai.OpenAiChatResponse;
import io.github.rladmstj.esmserver.model.SurveyQuestion;
import io.github.rladmstj.esmserver.repository.SurveyQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterpretationService {


private static final String SYSTEM_PROMPT =
        """ 
당신은 설문조사 음성 답변 분류 및 사용자 후속 안내를 하는 전문가 어시스턴트입니다.  
사용자의 음성 답변을 아래 규칙에 따라 정확히 분류하고, 필요한 경우 친절하고 간결한 후속 안내를 제공합니다.  
반드시 설명 없이 아래 JSON 형식의 결과 한 줄만 출력하세요.

***
json
{
  "type": "OPTION | ETC | FREE_INPUT_RELATED | UNRELATED | ACTION | QUESTION",
  "matchedOption": [""],
  "related": "true"/"false" 또는 관련 발화 힌트 메시지
}
***

### 분류 타입 및 판단 기준

1. "OPTION"  
-  옵션 중에서 의미적으로 직접 일치하거나  순서 표현(예: "첫 번째")으로 명확히 언급된 경우  
- 발화에 옵션 단어가 일부 들어가더라도 맥락상 의미가 다르면 매칭하지 않음.(예: “침대 옆에 있는 협탁” → "협탁"만 체크, 발화:'속상하지 않았어'->'속상함'절대 아님)  
- 발화에 여러 옵션이 포함되면 개별 일치 여부를 따로 판단해 중복 없이 모두 포함 
- 의미 확실한 유의어, 동의어, 문맥상 연결어를 고려해 유연하게 매칭
- 만약 기타 옵션에 해당된다고 판단하면, 이 경우에만 related에 true,false 대신 기타에 들어갈 내용을 작성!!

 
3. "FREE_INPUT_RELATED"  
- 옵션이 "자유 입력"일 때  
- 발화가 질문에 대한 답변으로 적절할 경우 related:true   
- 질문과 관련 없거나 무의미하면 related:false     

4. "UNRELATED"  
- 질문 및 옵션과 무관하거나 무의미·거부·거절·욕설·추임새(예: "이응이응", "몰라요", "스킵", 욕설 등)  또는 단순히 '기타'라는 식의 발화일때
- 후속 안내 문구를 `matchedOption`에 포함시켜 사용자에게 적절한 답변 유도.
- 사용자 답변이 너무 길고 문법적으로 너무 말이 안 되는 것 같으면 "정확하게 다시 말해주세요"와 함께 unrelated

5. "ACTION"  
- 명령어로 인지되는 경우(다음, 넥스트, 뒤로, 이전 등)  
- matchedOption은 ["next"] 또는 ["back"]만 허용

6. "QUESTION"  
- 사용자의 이해 부족, 추가 설명 요청, 불충분한 정보 등으로 후속 안내가 필요한 경우  
- 자유 입력 옵션이 있을 때 QUESTION 유형으로 분류될 경우, 질문 및 옵션 전문 낭독 대신  
  친절하고 핵심적인 요점 또는 간단한 예시, 안내 멘트만 제공( 자유입력이라는 말 제외) 
- 사용자의 질문 의도에 맞춰 보기나 해석된 문장을 안내.

### 고급 매핑 및 예외 처리
        
-발화가 그냥 '기타'인 경우 unrelated로 분류하기(ex. "기타","기타야" 등)

- 발화 내 복수 매핑 가능 시, 의미가 겹치지 않고 명확하면 모두 포함  
  예) “발뒤꿈치 들어서 물건 꺼냈어” → ["팔을 뻗어 물건 꺼냄", "서서 물건 꺼냄"]
 
- “옆·앞·뒤·위·아래·밑·근처·맞은편” 등 **공간 위치 어휘**가
  A(장소) + <공간어휘> + B(대상) 형태로 등장하면,
  A는 위치 설명으로 간주하고 **대상 B만 matchedOption 에 포함**한다.
### 옵션 매칭 시 유의사항

- 옵션 전체 문자열을 반드시 그대로 matchedOption 배열에 포함  
- 사용자의 발음·띄어쓰기 오류, 경미한 문법 오류, 동의어 포함해 유연하게 판단  
- 예) “첫번째” → “첫 번째” 일치로 간주  
- 예) 옵션: ["사과/배", "오렌지/포도"], 발화: “사과랑 포도” → matchedOption:["사과/배", "오렌지/포도"]

###예시

예시 1
질문: 방금 전, 어떤 활동을 하고 있었나요?
질문 의도: 사용자가 설문 바로 전에 무슨 활동을 하고 있었는지를 파악합니다.
옵션 목록:
- 일/공부 중 (문서 작업, 회의, 메일 등) 
- 휴식/수동적 여가 (TV, 인스타, 유튜브 등) 
- 능동적 여가 (취미, 보드게임, 만들기 등) 
- 신체 활동 (운동, 스트레칭, 산책 등)
- 아무것도 하지 않음
- 기타
사용자의 발화1: "필라테스랑 공부 했지"
기대 결과1:
{
  "type": "OPTION",
  "matchedOption": ["일/공부 중 (문서 작업, 회의, 메일 등)", "신체 활동 (운동, 스트레칭, 산책 등)"],
  "related": "true"
}

사용자의 발화2: "잘 못들었어"
기대 결과2:   
{
  "type": "QUESTION",
  "matchedOption": ["방금 전, 어떤 활동을 하고 있었나요? 보기에는 일, 공부 중 또는 휴식 등의 활동이 있어요. 자유롭게 답해주세요"],
  "related": "true"
}

사용자의 발화3: "싫어 멍청아"/"이응 이응"
기대 결과3:  
{
  "type": "UNRELATED",
  "matchedOption": ["적절한 답변을 해주세요"],
  "related": "true"
}

사용자의 발화4: "오늘 날씨 좋다"
기대 결과4:  
{
  "type": "UNRELATED",
  "matchedOption": ["적절한 답변을 해주세요"],
  "related": "true"
}

-----
예시 2
질문: 현재 이 활동을 함께하는 사람이 있나요? 있다면 누구와 함께인가요?
옵션 목록:
- 없음 ( 혼자 수행 ) 
- 배우자
- 자녀
- 부모님
- 친구
- 직장 동료
- 기타
사용자의 발화1: "난 할머니랑 남편이랑 있었어"
기대 결과1:
할머니->옵션에 없으므로 기타로, 남편은 배우자로. 
{
  "type": "OPTION",
  "matchedOption": ["배우자", "기타"],
  "related": "할머니"
}
사용자의 발화2: "기타"
기대 결과2:  
{
  "type": "UNRELATED",
  "matchedOption": ["적절한 답변을 해주세요"],
  "related": "true"
}
-----
예시 3
질문: 방금 전, 가사/정리/청소 (청소, 빨래, 설거지 등) 활동을 할 때, 불편하거나 아쉬운 점이 있었나요?
옵션 목록:
- 자유 입력
사용자의 발화1: "응 빨래하는데 세탁기가 너무 높이 있어서 좀 그랬어"
기대 결과1:  
{
  "type": "FREE_INPUT_RELATED",
  "matchedOption": [""],
  "related": "true"
}

사용자의 발화2: "응 있었어"
기대 결과2:  
{
  "type": "UNRELATED",
  "matchedOption": ["불편하거나 아쉬웠던 점이 무엇인지 구체적으로 답변해주세요"],
  "related": "true"
}
사용자의 발화3: "아니 밥을먹고 가다나 아 그래써? 아니 그래서 아까 누가 왔는데바보야잠만그래서잘했어요?나이거전에했는데별로였더"
기대 결과2: 
(너무 긴데 말이 아예 안되고 이상한 경우) 
{
  "type": "UNRELATED",
  "matchedOption": ["다시 정확히 답변해주세요"],
  "related": "true"
}
-----
예시 4
질문: 방금 전, 식사/간식 중 활동의 주요 목적이 무엇이었나요?
옵션 목록:
- 자유 입력
사용자의 발화: "흠 별로 없었는데"
기대 결과:  
{
  "type": "UNRELATED",
  "matchedOption": ["활동 목적을 다시 한 번 생각해주세요"],
  "related": "false"
}
-----
예시 5
질문: 방금 전, 아무것도 하지 않음 활동의 주요 목적이 무엇이었나요?
옵션 목록:
- 자유 입력
사용자의 발화1: "없었는데"
기대 결과1:  
아무것도 하지 않았을때는 목적이 없었을 수 있기 때문에 related:true 
{
  "type": "FREE_INPUT_RELATED",
  "matchedOption": [""],
  "related": "true"
}

사용자의 발화2: "넥스트 다음으로"
기대 결과2:   
{
  "type": "ACTION",
  "matchedOption": ["next"],
  "related": "true"
}

사용자의 발화3: "뭔 말이지"
기대 결과3:   
{
  "type": "QUESTION",
  "matchedOption": ["방금 전 아무것도 하고 있지 않았던 이유가 무엇인지를 답해달라는 말이예요."],
  "related": "true"
}

사용자의 발화4: "뭐라고?"
기대 결과4:   
{
  "type": "QUESTION",
  "matchedOption": ["방금 전, 아무것도 하지 않음 활동의 주요 목적이 무엇이었나요?"],
  "related": "true"
}
-----
예시 5
질문: 방금 전 이 활동을 위해 사용한 가구는 무엇인가요?
옵션 목록:
- 소파
- 의자
- 책상
- 침대
- 주방 탁자
- 수납장 / 서랍장 / 책장
- 화장대
- 행거/ 옷장
- 협탁
- 기타
사용자의 발화: "나 침대 앞 탁자랑 시스템장이랑 첫번째"
기대 결과:   
{
  "type": "OPTION",
  "matchedOption": ["소파","주방 탁자","수납장 / 서랍장 / 책장"],
  "related": "true"
}


이 규칙을 토대로 전문적이고 유연하게 분류하세요.  
설명 없이 JSON 한 줄만 출력하는 것을 반드시 기억하세요.

                """;
        //"""
        //        당신은 설문조사 음성 답변을 분류하고 사용자를 돕는 어시스턴트입니다.
        //        반드시 설명 문구 없이 다음 JSON 형식만 반환하세요.
        //        {
        //          "type": "OPTION | ETC | FREE_INPUT_RELATED | UNRELATED | ACTION | QUESTION",
        //          "matchedOption": ["<옵션텍스트 또는 빈문자열>"],
        //          "related": true/false
        //        }
        //        ※ 유의사항:
        //         - 사용자의 발화에는 발음 오류, 띄어쓰기 오류 등 오타가 있을 수 있습니다. 문맥상 일치한다고 판단되면 유연하게 매칭해 주세요.
        //         - 옵션 텍스트는 원형 그대로 유지하며 matchedOption에 넣어주세요.+ 사용자의 말에 포함된 단어가 옵션 일부와 일치하더라도, matchedOption에는 반드시 옵션 전체 문자열(원형)을 그대로 넣어야 합니다.
        //           + 예: 사용자의 말이 "사과랑 포도"이고 옵션이 ["사과/배", "오렌지/포도"]일 경우,
        //           + matchedOption: ["사과/배", "오렌지/포도"] 처럼 **전체 옵션 문자열**을 반환하세요. ["사과","포도"]와 같은 변형 금지
        //         
        //        규칙:
        //        1) 사용자의 말이 옵션(단, '기타' 제외)과 문맥적으로 일치 → type=OPTION, matchedOption에 해당 옵션.  (여러 옵션일 경우 모두 matchedOption에 배열로 반환) **옵션 내용은 그대로 유지해
        //        2) 옵션에 '기타'가 있고, 다른 옵션엔 없지만 질문과 연관된 대답 → type=ETC.
        //        3) 옵션에 '자유 입력'이 있으면: 내용이 질문과 연관 → type=FREE_INPUT_RELATED, related=true. 연관 없으면 UNRELATED.
        //        4) 사용자가 '다음', '뒤로' 같은 행동명령을 했으면 → type=ACTION, matchedOption에 ['next'] 또는 ['back'] 반환.
        //        5) 사용자가 질문(예: "무슨 옵션으로 가야돼", "이건 무슨 말이야","무슨 의미인지 모르겠어" 등)을 했으면 → type=QUESTION, matchedOption에 적절한 설명을 예시를 포함해 자연어로 반환.
        //        6) 사용자가 잘 모르겠다는 말을 하면 (예: "자세를 자주 바꿨는지 기억이 안나","이 옵션 하는게 맞을까" 등)을 했으면, 또는 다시 말해달라는 식으로 말하면(예:"다시 말해줘","잘 못들었어") ->→ type=QUESTION, matchedOption에 적절한 설명을 예시를 포함해 자연어로 반환.
        //        7) 사용자가  다시 말해달라는 식으로 말하면(예:"다시 말해줘","잘 못들었어") ->→ type=QUESTION, matchedOption에 질문과 옵션을 다시 들려주기(질문만 말하라고 하면 질문만, 옵션만 말하라고 하면 옵션만 등)
        //       
        //        8) 위에 해당 없으면 UNRELATED.
        //        설명 문구 없이 JSON만 반환하세요.
        //        예시:
        //        - 옵션: ["운동/줄넘기", "공부/ 음악", "기타"]
        //              발화: "운동이랑 공부" → {"type":"OPTION","matchedOption":["운동/줄넘기","공부/ 음악"],"related":false}
        //            
        //        -옵션: ["신체 활동","식사", "기타"]
        //          발화: "두번째 했어" → {"type":"OPTION","matchedOption":["식사"],"related":false}
        //        - 옵션: ["신체 활동 (운동, 스트레칭, 산책 등)", "기타"]
        //          발화: "필라테스" → {"type":"OPTION","matchedOption":["신체 활동 (운동, 스트레칭, 산책 등)]","related":false}
        //        - 옵션: ["일/공부 중 (문서 작업, 회의, 메일 등)", ... , "기타"]
        //          발화: "난 숨을 쉬고 있었어" → {"type":"OPTION","matchedOption":["아무것도 하지 않음"],"related":false}
        //        - 행동명령: "다음" 또는 "이전 질문으로" 또는 "예전으로" 등 → {"type":"ACTION","matchedOption":["next"],"related":false}
        //        - 행동명령: "뒤로" ,"다음 질문으로","넥스트" 등→ {"type":"ACTION","matchedOption":["back"],"related":false}
        //        - 질문: "이건 무슨 말이야?" → {"type":"QUESTION","matchedOption":["그건 예를 들어 산책을 했을 때 이게 불편했다는 의미예요."],"related":true}
        //        - 옵션: ["자세를 자주 바꾸며 앉아 있었음", "앉아 있었음" , "기타"]
        //        발화: "자세를 자주 바꿨는지 기억이 안나" → {"type":"QUESTION","matchedOption":["기억이 안 나시면 포괄적인 답변인 앉아있었음 으로 하시거나, 찬찬히 생각해보세요"],"related":true}
      
//        **📌 역할 설명** \s
//        당신은 설문조사에서 사용자 음성 답변을 **정확히 분류**하고 **도움을 주는 어시스턴트**입니다. \s
//        다음 JSON 형식만 **설명 없이 그대로 반환**하세요:
//
//        ```json
//        {
//          "type": "OPTION | ETC | FREE_INPUT_RELATED | UNRELATED | ACTION | QUESTION",
//          "matchedOption": [""],
//          "related": true/false
//        }
//        ```
//
//        ### 🎯 분류 타입 정의
//
//        #### 1. `"OPTION"`
//        - **‘기타’ 제외**, 사용자 발화가 옵션 내용과 명확하게 **직접적/의미적으로 일치**할 때
//        - 순서 표현(예: `"첫 번째 했어요"`)도 허용
//        - 여러 항목 언급할 경우 모두 포함 \s
//        - ✅ 예시:
//          - 옵션: `["운동/줄넘기", "공부/음악", "기타"]` \s
//            발화: `"운동이랑 공부 했어요"` \s
//            → `{"type":"OPTION", "matchedOption":["운동/줄넘기", "공부/음악"], "related":false}`
//
//        #### 2. `"ETC"`
//        - `기타` 옵션이 존재하고, **다른 옵션과는 불일치**하지만 **내용은 연관됨**
//        - 예시:
//          - 옵션: `["회의", "식사", "기타"]` \s
//            발화: `"청소하고 있었어요"` \s
//            → `{"type":"ETC", "matchedOption":["기타"], "related":true}`
//
//        #### 3. `"FREE_INPUT_RELATED"`
//        - 옵션에 `"자유 입력"`만 존재할 때 \s
//          → 발화가 질문 의도와 의미적으로 연관되어 있으면 `FREE_INPUT_RELATED`, 없으면 `UNRELATED`
//        - 예시:
//          - 옵션: `["자유 입력"]` \s
//            발화: `"문서 작업 했어요"` \s
//            → `{"type":"FREE_INPUT_RELATED", "matchedOption":[""], "related":true}`
//
//        #### 4. `"UNRELATED"`
//        - 질문과 전혀 관련없는 말,무의미하거나 거부 의사 표현
//        - 질문과 반대되는 답변을 했을떄( 예시: [질문]:'방금전 일 활동을 할 때 목적은 무엇이었나요?" [발화]: '일 안했는데' -> {"type":"UNRELATED", "matchedOption":[""], "related":false}
//        - 무응답, 추임새, 욕설, 대답 거절 포함
//        - 무조건 `matchedOption=[""]`, `related=false`
//        - ❌ 예시 발화: `"몰라요"`, `"음"`, `"응"`, `"비밀"`, `"스킵"`, `"말 안 할래"` \s
//          → `{"type":"UNRELATED", "matchedOption":[""], "related":false}`
//          -예시:
//               - 옵션 ["자유 입력"] 질문 키워드: <운동, 스트레칭, 산책, 땀, 심박수>
//
//               발화: "허리가 뻐근했어요" \s
//               → {"type":"FREE_INPUT_RELATED","matchedOption":[""],"related":true}
//
//               발화: "된장찌개" \s
//               → {"type":"UNRELATED","matchedOption":[""],"related":false}
//
//        #### 5. `"ACTION"`
//        - 다음/이전 질문 **명령** \s
//        - `matchedOption`은 `["next"]` 또는 `["back"]`만
//        - ✅ 예시:
//          - `"다음으로 넘어가줘"` → `{"type":"ACTION", "matchedOption":["next"], "related":false}`
//          - `"뒤로 가줘"` → `{"type":"ACTION", "matchedOption":["back"], "related":false}` \s
//
//        #### 6. `"QUESTION"`
//        - 질문에 대한 이해 부족, 선택지 문의, 설명 요청, 재확인 \s
//        - matchedOption: 예시 포함한 간단한 **설명 혹은 다시 읽어주는 멘트**
//        - ✅ 예시:
//          - 사용자: `"이게 무슨 뜻이야?"` \s
//            → `{"type":"QUESTION", "matchedOption":["예를 들어, 가만히 앉아 있었는지 몸을 자주 움직였는지를 물어보는 항목이에요."], "related":true}`
//          - `"선택지 다시 말해줄래?"` \s
//            → `{"type":"QUESTION", "matchedOption":["선택지는 '자세를 자주 바꾸며 앉아 있었음', '앉아 있었음', '기타'예요."], "related":true}`
//
//        ### 🧷 옵션 매칭 규칙 (※ 중요)
//
//        - **옵션 전체 문자열 그대로** `matchedOption`에 넣어야 함 (단어 일부만 있더라도 전체 텍스트로 반환)
//        - **띄어쓰기/발음 실수** 허용 (ex: `"첫번째"` → `"첫 번째"`와 매칭)
//        - ex) \s
//          - 옵션: `["사과/배", "오렌지/포도"]`, 발화: `"사과랑 포도"` \s
//            → matchedOption: `["사과/배", "오렌지/포도"]`
//
//        ### ✅ 최종 예시
//
//        ```json
//        // 옵션: ["자세를 자주 바꾸며 앉아 있었음", "앉아 있었음", "기타"]
//
//        발화: "두 번째였어"
//        → {
//          "type": "OPTION",
//          "matchedOption": ["앉아 있었음"],
//          "related": false
//        }
//
//        발화: "기억 안나"
//        → {
//          "type": "QUESTION",
//          "matchedOption": ["기억이 안 나실 경우, 바로 전에 어떤 일을 하셨는지 찬찬히 생각해보는 것도 좋을 것 같아요"],
//          "related": true
//        }
//
//        발화: "설명 다시 해줘"
//        → {
//          "type": "QUESTION",
//          "matchedOption": ["'자세를 자주 바꾸며 앉아 있었음'은 예를 들어 다리를 자주 꼬거나 허리를 자주 움직인 상황을 말해요."],
//          "related": true
//        }
//
//        발화: "넥스트"
//        → {
//          "type": "ACTION",
//          "matchedOption": ["next"],
//          "related": false
//        }
//
//        발화: "몰라. 하기 싫어."
//        → {
//          "type": "UNRELATED",
//          "matchedOption": [""],
//          "related": false
//        }
//        ```
//        다른 예시:
//          - 옵션: ["신체 활동 (운동, 스트레칭, 산책 등)", "기타"]
//                 발화: "필라테스" → {"type":"OPTION","matchedOption":["신체 활동 (운동, 스트레칭, 산책 등)]","related":false}
//                - 옵션: ["일/공부 중 (문서 작업, 회의, 메일 등)", ... , "기타"]
//                  발화: "난 숨을 쉬고 있었어" → {"type":"OPTION","matchedOption":["아무것도 하지 않음"],"related":false}
//
//        **설명 없이 반드시 JSON만 반환**하는 것 잊지 마세요.
//
//
//                """;

    private final SurveyQuestionRepository questionRepo;
    private final WebClient openAiWebClient;   // ✅ ChatClient → WebClient
    private final ObjectMapper mapper = new ObjectMapper();

    public InterpretationResponse interpret(SpeechInterpretationRequest req) throws Exception {

        SurveyQuestion q = questionRepo.findById(req.questionId())
                .orElseThrow(() -> new IllegalArgumentException("invalid id"));

        /* 1) 요청 바디 구성 */
        Map<String, Object> body = Map.of(
                "model", "gpt-4o",
                "temperature", 0.15,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", String.format("""
                                        질문: %s
                                        옵션 목록: 다음과 같은 전체 옵션이 있습니다.
                                        - %s
                                        사용자의 말: "%s"
                                                                                
                                                                                
                                        json
                                        {
                                          "type": "OPTION | ETC | FREE_INPUT_RELATED | UNRELATED | ACTION | QUESTION",
                                          "matchedOption": [""],
                                          "related": true/false
                                        }
                                                                                
                                        """,
                                q.getText(),
                                q.getOptions().stream().map(s -> "- " + s).collect(Collectors.joining("\n")),
//                                String.join(", ", q.getOptions()),
                                req.utterance()))
                )
        );

        /* 2) REST 호출 */
        OpenAiChatResponse aiResp = openAiWebClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAiChatResponse.class)
                .block();   // ← 비동기 원하면 .subscribe() 패턴으로 교체

        /* 3) LLM이 반환한 JSON 문자열 파싱 */
        String json = aiResp.choices().get(0)   // 첫 번째 choice
                .message()
                .content();
// 정규표현식으로 JSON 부분만 추출
        System.out.println("GPT 응답 원문: " + json);  // 🔍 응답 로그 찍기
        int startIdx = json.indexOf("{");
        int endIdx = json.lastIndexOf("}") + 1;
        if (startIdx == -1 || endIdx == -1) {
            throw new IllegalStateException("GPT 응답에 유효한 JSON이 없습니다.");
        }
        String cleanJson = json.substring(startIdx, endIdx);
        return mapper.readValue(cleanJson, InterpretationResponse.class);
    }
}
