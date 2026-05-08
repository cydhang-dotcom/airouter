#!/bin/zsh
set -euo pipefail

if [[ -z "${OPENAI_COMPAT_API_KEY:-}" ]]; then
  echo "Missing OPENAI_COMPAT_API_KEY" >&2
  exit 1
fi

if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "Missing DEEPSEEK_API_KEY" >&2
  exit 1
fi

ROUNDS="${1:-3}"
OUTPUT_PATH="${2:-docs/model-stability-eval-2026-05-09.jsonl}"

mkdir -p "$(dirname "$OUTPUT_PATH")"
: > "$OUTPUT_PATH"

PROMPT_ENTERPRISE_ACCOUNTING=$(cat <<'EOF'
你是一位拥有 10 年经验的上海工商注册专家。对“企业代账服务”，依据中国大陆最新工商法规进行严格的合规性分析。

核心原则：
1. 仅当业务明确涉及需要行政许可或备案的项目时，才标记为需要资质。
2. 不要过度联想，不要把软件开发类业务误判为需要许可。
3. 经营范围必须使用标准规范表述。

分析任务：
1. 生成 5-8 个最核心的标准经营范围条目，按重要性排序，逗号分隔。
2. 判断该业务是否需要许可或资质，若需要请给出证书全称。
3. 判断是否涉及强监管敏感领域，只能使用以下代码：
   education, medical, food, import_export, media, finance, hr_service, construction, chemical, icp, other

直接返回 JSON，不要包含 Markdown，不要输出解释文字。
字段名必须严格使用以下键名，不能改写、缩写或替换：
- suggestedScope: string
- needLicense: "yes" | "no"
- licenseDetail: string
- hasSensitiveTypes: "yes" | "no"
- sensitiveTypes: string[]
- otherSensitiveType: string
EOF
)

PROMPT_HR_OUTSOURCING=$(cat <<'EOF'
你是一位拥有 10 年经验的上海工商注册专家。对“人力资源外包服务（不含劳务派遣）”，依据中国大陆最新工商法规进行严格的合规性分析。

核心原则：
1. 仅当业务明确涉及需要行政许可或备案的项目时，才标记为需要资质。
2. 若业务描述明确排除了某项高监管业务，不要再把它判进去。
3. 经营范围必须使用标准规范表述。

分析任务：
1. 生成 5-8 个最核心的标准经营范围条目，按重要性排序，逗号分隔。
2. 判断该业务是否需要许可或资质，若需要请给出证书全称。
3. 判断是否涉及强监管敏感领域，只能使用以下代码：
   education, medical, food, import_export, media, finance, hr_service, construction, chemical, icp, other

直接返回 JSON，不要包含 Markdown，不要输出解释文字。
字段名必须严格使用以下键名，不能改写、缩写或替换：
- suggestedScope: string
- needLicense: "yes" | "no"
- licenseDetail: string
- hasSensitiveTypes: "yes" | "no"
- sensitiveTypes: string[]
- otherSensitiveType: string
EOF
)

PROMPT_EDU_SOFTWARE=$(cat <<'EOF'
你是一位拥有 10 年经验的上海工商注册专家。对“在线教育软件开发与销售，不提供培训授课服务”，依据中国大陆最新工商法规进行严格的合规性分析。

核心原则：
1. 仅当业务明确涉及需要行政许可或备案的项目时，才标记为需要资质。
2. 不要把软件开发销售误判为教育培训。
3. 经营范围必须使用标准规范表述。

分析任务：
1. 生成 5-8 个最核心的标准经营范围条目，按重要性排序，逗号分隔。
2. 判断该业务是否需要许可或资质，若需要请给出证书全称。
3. 判断是否涉及强监管敏感领域，只能使用以下代码：
   education, medical, food, import_export, media, finance, hr_service, construction, chemical, icp, other

直接返回 JSON，不要包含 Markdown，不要输出解释文字。
字段名必须严格使用以下键名，不能改写、缩写或替换：
- suggestedScope: string
- needLicense: "yes" | "no"
- licenseDetail: string
- hasSensitiveTypes: "yes" | "no"
- sensitiveTypes: string[]
- otherSensitiveType: string
EOF
)

PROMPT_CROSSBORDER=$(cat <<'EOF'
你是一位拥有 10 年经验的上海工商注册专家。对“跨境电商进出口代理服务”，依据中国大陆最新工商法规进行严格的合规性分析。

核心原则：
1. 仅当业务明确涉及需要行政许可或备案的项目时，才标记为需要资质。
2. 对进出口、海关、外汇等相关事项要准确识别，但不要夸大到不相关许可。
3. 经营范围必须使用标准规范表述。

分析任务：
1. 生成 5-8 个最核心的标准经营范围条目，按重要性排序，逗号分隔。
2. 判断该业务是否需要许可或资质，若需要请给出证书全称。
3. 判断是否涉及强监管敏感领域，只能使用以下代码：
   education, medical, food, import_export, media, finance, hr_service, construction, chemical, icp, other

直接返回 JSON，不要包含 Markdown，不要输出解释文字。
字段名必须严格使用以下键名，不能改写、缩写或替换：
- suggestedScope: string
- needLicense: "yes" | "no"
- licenseDetail: string
- hasSensitiveTypes: "yes" | "no"
- sensitiveTypes: string[]
- otherSensitiveType: string
EOF
)

MODEL_NAMES=("kimi-k2-0905-preview" "kimi-k2-turbo-preview" "deepseek-v4-flash")
MODEL_BASE_URLS=("https://api.moonshot.cn/v1" "https://api.moonshot.cn/v1" "https://api.deepseek.com")
MODEL_KEYS=("$OPENAI_COMPAT_API_KEY" "$OPENAI_COMPAT_API_KEY" "$DEEPSEEK_API_KEY")

CASE_IDS=("enterprise_accounting" "hr_outsourcing" "edu_software" "crossborder_trade")
CASE_TITLES=("企业代账服务" "人力资源外包（不含劳务派遣）" "在线教育软件开发与销售（不授课）" "跨境电商进出口代理")
CASE_PROMPTS=("$PROMPT_ENTERPRISE_ACCOUNTING" "$PROMPT_HR_OUTSOURCING" "$PROMPT_EDU_SOFTWARE" "$PROMPT_CROSSBORDER")

case_expectations() {
  local case_id="$1"
  case "$case_id" in
    enterprise_accounting)
      echo "yes|代理记账|finance|optional"
      ;;
    hr_outsourcing)
      echo "no||hr_service|optional"
      ;;
    edu_software)
      echo "no||education|optional"
      ;;
    crossborder_trade)
      echo "yes|海关,备案|import_export|required"
      ;;
  esac
}

normalize_yes_no() {
  local raw="${1:-}"
  if [[ "$raw" == "true" ]]; then
    echo "yes"
  elif [[ "$raw" == "false" ]]; then
    echo "no"
  else
    echo "${raw:l}"
  fi
}

validate_case() {
  local case_id="$1"
  local content="$2"
  local expectation
  expectation="$(case_expectations "$case_id")"
  local expected_need="${expectation%%|*}"
  local rest="${expectation#*|}"
  local license_tokens="${rest%%|*}"
  rest="${rest#*|}"
  local sensitive_type="${rest%%|*}"
  local sensitive_mode="${rest##*|}"

  local need_license
  need_license="$(echo "$content" | jq -r '.needLicense')"
  need_license="$(normalize_yes_no "$need_license")"
  local has_sensitive
  has_sensitive="$(echo "$content" | jq -r '.hasSensitiveTypes')"
  has_sensitive="$(normalize_yes_no "$has_sensitive")"
  local license_detail
  license_detail="$(echo "$content" | jq -r '.licenseDetail')"
  local sensitive_types
  sensitive_types="$(echo "$content" | jq -r '.sensitiveTypes | join(",")')"

  local semantic_pass="yes"
  local semantic_issues=()
  local format_pass="yes"
  local format_issues=()

  if [[ "$need_license" != "yes" && "$need_license" != "no" ]]; then
    format_pass="no"
    format_issues+=("needLicense not yes/no")
  fi

  if [[ "$has_sensitive" != "yes" && "$has_sensitive" != "no" ]]; then
    format_pass="no"
    format_issues+=("hasSensitiveTypes not yes/no")
  fi

  if ! echo "$content" | jq -e '.suggestedScope | type == "string"' >/dev/null; then
    format_pass="no"
    format_issues+=("suggestedScope not string")
  fi

  if ! echo "$content" | jq -e '.licenseDetail | type == "string"' >/dev/null; then
    format_pass="no"
    format_issues+=("licenseDetail not string")
  fi

  if ! echo "$content" | jq -e '.otherSensitiveType | type == "string"' >/dev/null; then
    format_pass="no"
    format_issues+=("otherSensitiveType not string")
  fi

  if ! echo "$content" | jq -e '.sensitiveTypes | type == "array"' >/dev/null; then
    format_pass="no"
    format_issues+=("sensitiveTypes not array")
  fi

  if [[ "$need_license" != "$expected_need" ]]; then
    semantic_pass="no"
    semantic_issues+=("needLicense=$need_license")
  fi

  if [[ "$expected_need" == "yes" && -n "$license_tokens" ]]; then
    local token
    for token in ${(s:,:)license_tokens}; do
      if [[ "$license_detail" != *"$token"* ]]; then
        semantic_pass="no"
        semantic_issues+=("licenseDetail=$license_detail")
        break
      fi
    done
  elif [[ "$expected_need" == "no" && -n "${license_detail// }" ]]; then
    semantic_pass="no"
    semantic_issues+=("licenseDetail should be blank")
  fi

  if [[ -z "$(echo "$content" | jq -r '.suggestedScope')" ]]; then
    semantic_pass="no"
    semantic_issues+=("suggestedScope blank")
  fi

  if [[ "$sensitive_mode" == "required" ]]; then
    if [[ "$has_sensitive" != "yes" || "$sensitive_types" != *"$sensitive_type"* ]]; then
      semantic_pass="no"
      semantic_issues+=("sensitiveTypes=$sensitive_types")
    fi
  elif [[ "$has_sensitive" == "yes" && "$sensitive_types" != *"$sensitive_type"* ]]; then
    semantic_pass="no"
    semantic_issues+=("sensitiveTypes=$sensitive_types")
  fi

  jq -n \
    --arg semanticPass "$semantic_pass" \
    --arg formatPass "$format_pass" \
    --arg semanticIssues "${(j:; :)semantic_issues}" \
    --arg formatIssues "${(j:; :)format_issues}" \
    '{
      semanticPass: ($semanticPass == "yes"),
      formatPass: ($formatPass == "yes"),
      semanticIssues: ($semanticIssues | if . == "" then [] else split("; ") end),
      formatIssues: ($formatIssues | if . == "" then [] else split("; ") end)
    }'
}

call_model() {
  local base_url="$1"
  local api_key="$2"
  local model="$3"
  local prompt="$4"
  local started ended elapsed body content
  started="$(perl -MTime::HiRes=time -e 'printf "%.0f", time()*1000')"
  body="$(curl -sS "$base_url/chat/completions" \
    -H "Authorization: Bearer $api_key" \
    -H "Content-Type: application/json" \
    -d "$(jq -n --arg model "$model" --arg prompt "$prompt" '{model:$model,messages:[{role:"user",content:$prompt}],temperature:0.1,response_format:{type:"json_object"}}')" \
    | iconv -f UTF-8 -t UTF-8//IGNORE \
    | perl -0pe 's/[\x00-\x08\x0B\x0C\x0E-\x1F]//g')"
  ended="$(perl -MTime::HiRes=time -e 'printf "%.0f", time()*1000')"
  elapsed="$((ended-started))"
  content="$(echo "$body" | jq -r '.choices[0].message.content' | iconv -f UTF-8 -t UTF-8//IGNORE | perl -0pe 's/[\x00-\x08\x0B\x0C\x0E-\x1F]//g')"
  jq -n \
    --argjson elapsedMs "$elapsed" \
    --arg contentRaw "$content" \
    '{elapsedMs: $elapsedMs, contentRaw: $contentRaw}'
}

print_case_summary() {
  local case_id="$1"
  local model="$2"
  local records="$3"
  local total semantic_ok format_ok avg_ms min_ms max_ms
  total="$(echo "$records" | jq 'length')"
  semantic_ok="$(echo "$records" | jq '[.[] | select(.review.semanticPass == true)] | length')"
  format_ok="$(echo "$records" | jq '[.[] | select(.review.formatPass == true)] | length')"
  avg_ms="$(echo "$records" | jq '[.[].elapsedMs] | add / length | floor')"
  min_ms="$(echo "$records" | jq '[.[].elapsedMs] | min')"
  max_ms="$(echo "$records" | jq '[.[].elapsedMs] | max')"
  echo "CASE=$case_id MODEL=$model total=$total semantic_pass=$semantic_ok/$total format_pass=$format_ok/$total avg_ms=$avg_ms min_ms=$min_ms max_ms=$max_ms"
}

for ((case_idx = 1; case_idx <= ${#CASE_IDS[@]}; case_idx++)); do
  case_id="${CASE_IDS[$case_idx]}"
  case_title="${CASE_TITLES[$case_idx]}"
  case_prompt="${CASE_PROMPTS[$case_idx]}"
  echo "=== $case_title ($case_id) ==="
  for ((model_idx = 1; model_idx <= ${#MODEL_NAMES[@]}; model_idx++)); do
    model="${MODEL_NAMES[$model_idx]}"
    base_url="${MODEL_BASE_URLS[$model_idx]}"
    api_key="${MODEL_KEYS[$model_idx]}"
    model_records='[]'
    for ((round = 1; round <= ROUNDS; round++)); do
      set +e
      response="$(call_model "$base_url" "$api_key" "$model" "$case_prompt" 2>&1)"
      response_status=$?
      set -e
      if [[ $response_status -ne 0 ]]; then
        record="$(jq -n \
          --arg caseId "$case_id" \
          --arg caseTitle "$case_title" \
          --arg model "$model" \
          --argjson round "$round" \
          --arg error "$response" \
          '{caseId:$caseId,caseTitle:$caseTitle,model:$model,round:$round,error:$error,review:{semanticPass:false,formatPass:false,semanticIssues:["request_failed"],formatIssues:["request_failed"]}}')"
      else
        content_raw="$(echo "$response" | jq -r '.contentRaw')"
        set +e
        content_json="$(printf '%s' "$content_raw" | jq -c . 2>/dev/null)"
        parse_status=$?
        if [[ $parse_status -eq 0 ]]; then
          review="$(validate_case "$case_id" "$content_json" 2>&1)"
          review_status=$?
        else
          review="$(jq -n --arg raw "$content_raw" '{semanticPass:false,formatPass:false,semanticIssues:["parse_failed"],formatIssues:["parse_failed"],rawContent:$raw}')"
          review_status=0
        fi
        set -e
        if [[ $review_status -ne 0 ]]; then
          review="$(jq -n --arg err "$review" '{semanticPass:false,formatPass:false,semanticIssues:["parse_failed"],formatIssues:["parse_failed",$err]}')"
        fi
        record="$(jq -n \
          --arg caseId "$case_id" \
          --arg caseTitle "$case_title" \
          --arg model "$model" \
          --argjson round "$round" \
          --argjson elapsedMs "$(echo "$response" | jq '.elapsedMs')" \
          --arg contentRaw "$content_raw" \
          --argjson review "$review" \
          '{caseId:$caseId,caseTitle:$caseTitle,model:$model,round:$round,elapsedMs:$elapsedMs,contentRaw:$contentRaw,review:$review}')"
      fi
      echo "$record" >> "$OUTPUT_PATH"
      model_records="$(echo "$model_records" | jq --argjson record "$record" '. + [$record]')"
    done
    print_case_summary "$case_id" "$model" "$model_records"
  done
  echo
done
