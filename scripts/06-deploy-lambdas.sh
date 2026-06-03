#!/usr/bin/env bash
# =============================================================================
# 06-deploy-lambdas.sh
# Faz o deploy (ou update) das 3 funções Lambda (fractals, grayscott, dna).
# =============================================================================
set -euo pipefail
source "$(dirname "$0")/aws-config.sh"
test_aws_cli || exit 1

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${LAMBDA_ROLE_NAME}"

info "A usar Role ARN: $ROLE_ARN"

deploy_lambda() {
    local func_name=$1
    local handler=$2
    local jar_path=$3

    if [ ! -f "$jar_path" ]; then
        err "JAR não encontrado: $jar_path. Corre 'mvn clean package' na raiz do projeto primeiro."
        exit 1
    fi

    info "A fazer deploy da Lambda: $func_name ..."

    if aws lambda get-function --function-name "$func_name" >/dev/null 2>&1; then
        info "  A função já existe. A atualizar o código..."
        aws lambda update-function-code \
            --function-name "$func_name" \
            --zip-file "fileb://$jar_path" >/dev/null
        
        # Wait until update is successful
        aws lambda wait function-updated --function-name "$func_name"
        
        aws lambda update-function-configuration \
            --function-name "$func_name" \
            --handler "$handler" \
            --timeout 60 \
            --memory-size 512 >/dev/null
        ok "  Função $func_name atualizada."
    else
        info "  A criar nova função..."
        aws lambda create-function \
            --function-name "$func_name" \
            --runtime java11 \
            --role "$ROLE_ARN" \
            --handler "$handler" \
            --timeout 60 \
            --memory-size 512 \
            --zip-file "fileb://$jar_path" >/dev/null
        
        aws lambda wait function-active-v2 --function-name "$func_name"
        ok "  Função $func_name criada."
    fi
}

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

deploy_lambda "fractals" "pt.ulisboa.tecnico.cnv.fractals.FractalsHandler::handleRequest" "$PROJECT_ROOT/fractals/target/fractals-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
deploy_lambda "grayscott" "pt.ulisboa.tecnico.cnv.grayscott.GrayScottHandler::handleRequest" "$PROJECT_ROOT/grayscott/target/grayscott-1.0.0-SNAPSHOT-jar-with-dependencies.jar"
deploy_lambda "dna" "pt.ulisboa.tecnico.cnv.dna.DnaHandler::handleRequest" "$PROJECT_ROOT/dna/target/dna-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

ok "Deploy de todas as funções Lambda concluído!"
