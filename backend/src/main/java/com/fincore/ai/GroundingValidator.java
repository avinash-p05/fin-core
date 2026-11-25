package com.fincore.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fincore.ai.AlertScopedTools.Evidence;
import com.fincore.ai.CopilotDtos.Citation;
import com.fincore.ai.CopilotDtos.CopilotDraft;
import com.fincore.ai.CopilotDtos.PaymentView;

/**
 * Checks a model answer against what the tools actually returned. Any doubt fails the answer:
 * the caller then discards it and forces ESCALATE (fail closed), so a hallucinated payment or a
 * wrong amount never reaches the analyst as if it were fact.
 *
 * <p>Rules: structured citations must name payments the model was shown, with exactly the amount
 * on record; the prose may only mention ids it was given (payments among them must be cited) and
 * money values that appear in the evidence. Totals or rounded amounts the model computed itself
 * cannot be verified, so they fail too; the prompt tells it to use counts instead.
 */
public final class GroundingValidator {

    public record Result(boolean grounded, List<String> errors) {
    }

    private static final Pattern UUID_PATTERN =
            Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A number with a currency marker before or after it, or an Indian unit (lakh/crore). A trailing
     * "INR" only counts as this number's currency when no other number follows it, so in
     * "transfer #2 INR 25000.00" the amount is 25000.00 (and gets checked), not 2. "L" for lakh is
     * upper case only.
     */
    private static final Pattern MONEY = Pattern.compile(
            "(₹|\\bINR\\b|\\bRs\\.?)?\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*(lakhs?\\b|lacs?\\b|crores?\\b|cr\\b|(?-i:L)\\b)?"
                    + "\\s*(INR\\b(?!\\s*[₹\\d])|rupees?\\b)?",
            Pattern.CASE_INSENSITIVE);

    private static final BigDecimal LAKH = new BigDecimal("100000");
    private static final BigDecimal CRORE = new BigDecimal("10000000");

    public Result validate(CopilotDraft draft, Evidence evidence) {
        List<String> errors = new ArrayList<>();
        if (draft == null) {
            return new Result(false, List.of("The model returned no structured answer"));
        }
        if (draft.summary() == null || draft.summary().isBlank()) {
            errors.add("Summary is empty");
        }
        if (draft.suggestedAction() == null) {
            errors.add("No suggested action");
        }
        if (draft.confidence() == null || draft.confidence() < 0.0 || draft.confidence() > 1.0) {
            errors.add("Confidence must be between 0 and 1");
        }

        List<Citation> citations = draft.citations() == null ? List.of() : draft.citations();
        Set<UUID> cited = new java.util.HashSet<>();
        for (Citation c : citations) {
            checkCitation(c, evidence, errors);
            if (c != null && c.paymentId() != null) {
                cited.add(c.paymentId());
            }
        }

        String prose = prose(draft);
        checkIds(prose, evidence, cited, errors);
        checkAmounts(prose, evidence.knownAmounts(), errors);
        return new Result(errors.isEmpty(), List.copyOf(errors));
    }

    private static void checkCitation(Citation c, Evidence evidence, List<String> errors) {
        if (c == null || c.paymentId() == null) {
            errors.add("A citation has no payment id");
            return;
        }
        PaymentView shown = evidence.payments().get(c.paymentId());
        if (shown == null) {
            errors.add("Cites payment " + c.paymentId() + ", which no tool returned");
            return;
        }
        if (!evidence.scopeAccounts().contains(shown.sourceAccountId())
                && !evidence.scopeAccounts().contains(shown.destinationAccountId())) {
            errors.add("Cites payment " + c.paymentId() + ", which does not involve this alert's accounts");
        }
        if (c.amount() == null) {
            errors.add("Citation of payment " + c.paymentId() + " has no amount");
        } else if (c.amount().compareTo(shown.amount()) != 0) {
            errors.add("States " + c.amount().toPlainString() + " for payment " + c.paymentId()
                    + "; the record says " + shown.amount().toPlainString());
        }
    }

    private static void checkIds(String prose, Evidence evidence, Set<UUID> cited, List<String> errors) {
        Set<UUID> known = evidence.knownIds();
        Matcher m = UUID_PATTERN.matcher(prose);
        while (m.find()) {
            UUID id = UUID.fromString(m.group());
            if (!known.contains(id)) {
                errors.add("Mentions id " + id + ", which is not in the evidence");
            } else if (evidence.payments().containsKey(id) && !cited.contains(id)) {
                errors.add("Mentions payment " + id + " without citing it");
            }
        }
    }

    private static void checkAmounts(String prose, Set<BigDecimal> known, List<String> errors) {
        // Ids contain digit runs that would look like numbers; drop them first.
        String text = UUID_PATTERN.matcher(prose).replaceAll(" ");
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            boolean currency = m.group(1) != null || m.group(4) != null;
            String unit = m.group(3);
            if (!currency && unit == null) {
                continue; // a plain count or score, not money
            }
            BigDecimal value;
            try {
                value = new BigDecimal(m.group(2).replace(",", ""));
            } catch (NumberFormatException e) {
                errors.add("Unreadable amount '" + m.group().trim() + "'");
                continue;
            }
            if (unit != null) {
                value = value.multiply(unit.toLowerCase().startsWith("c") ? CRORE : LAKH);
            }
            if (!known.contains(value.stripTrailingZeros())) {
                errors.add("States amount " + value.toPlainString() + ", which matches no payment, balance or threshold");
            }
        }
    }

    private static String prose(CopilotDraft draft) {
        StringBuilder sb = new StringBuilder();
        if (draft.summary() != null) {
            sb.append(draft.summary());
        }
        if (draft.riskFactors() != null) {
            draft.riskFactors().forEach(f -> sb.append('\n').append(f));
        }
        return sb.toString();
    }
}
