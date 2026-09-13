package hudson.plugins.ec2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;
import software.amazon.awssdk.services.ec2.model.InstanceType;

/**
 * Precedence between a label hot spare rule and the template it governs. The rule wins by default;
 * a template only takes over when the rule opts in and the template sets the value explicitly.
 */
@WithJenkins
class HotSpareConfigByLabelTest {

    private static final String LABEL = "ttt";

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        r = rule;
    }

    @Test
    void testRuleMatchesTemplatesCarryingItsLabel() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        EC2Cloud cloud = cloud(rule, template("30", 0));

        assertNotNull(cloud.getHotSpareConfigFor(computer(cloud, template("30", 0))));
        assertNull(
                cloud.getHotSpareConfigFor(computer(cloud, templateWithLabels("30", 0, "unrelated"))),
                "a rule must not govern a template that does not carry its label");
    }

    @Test
    void testLabelRuleIdleTimeoutWinsOverTemplateByDefault() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 0))), equalTo(45));
    }

    @Test
    void testTemplateIdleTimeoutWinsWhenOverrideIsAllowedAndSet() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(
                "null keeps the retention strategy value, which came from the template",
                cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 0))),
                nullValue());
    }

    /**
     * The regression guard against the override nullifying the rule: an unset template value must
     * not beat the rule, or every default template would silently win.
     */
    @Test
    void testUnsetTemplateIdleTimeoutFallsBackToRuleEvenWithOverrideAllowed() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template(null, 0))), equalTo(45));
        assertThat(
                "a blank string is unset, not zero",
                cloud.resolveIdleTerminationMinutes(computer(cloud, template("  ", 0))),
                equalTo(45));
    }

    @Test
    void testNoRuleLeavesTheTemplateValueInPlace() throws Exception {
        EC2Cloud cloud = cloud(null);

        assertThat(cloud.resolveIdleTerminationMinutes(computer(cloud, template("30", 7))), nullValue());
        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(7));
    }

    @Test
    void testLabelRuleGracePeriodWinsOverTemplateByDefault() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setGracePeriodMinutes(20);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(20));
    }

    @Test
    void testTemplateGracePeriodWinsWhenOverrideIsAllowedAndSet() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setGracePeriodMinutes(20);
        rule.setAllowTemplateGracePeriodOverride(true);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 7))), equalTo(7));
        assertThat(
                "a template grace period of 0 is unset, so the rule still applies",
                cloud.resolveGracePeriodMinutes(computer(cloud, template("30", 0))),
                equalTo(20));
    }

    /**
     * The discard flag has no precedence rule of its own: it comes from whichever grace period was
     * selected, so a grace period and its behaviour are never mixed from two sources.
     */
    @Test
    void testDiscardFlagFollowsTheWinningGracePeriod() throws Exception {
        HotSpareConfigByLabel labelWins = new HotSpareConfigByLabel(LABEL);
        labelWins.setGracePeriodMinutes(20);
        labelWins.setDiscardAfterGracePeriod(false);
        EC2Cloud cloudWhereLabelWins = cloud(labelWins);
        SlaveTemplate discardingTemplate = template("30", 7);
        discardingTemplate.setDiscardAfterGracePeriod(true);

        assertThat(
                "the label rule supplied the grace period, so it supplies the flag too",
                cloudWhereLabelWins.resolveDiscardAfterGracePeriod(computer(cloudWhereLabelWins, discardingTemplate)),
                equalTo(false));

        HotSpareConfigByLabel templateWins = new HotSpareConfigByLabel(LABEL);
        templateWins.setGracePeriodMinutes(20);
        templateWins.setDiscardAfterGracePeriod(true);
        templateWins.setAllowTemplateGracePeriodOverride(true);
        EC2Cloud cloudWhereTemplateWins = cloud(templateWins);
        SlaveTemplate keepingTemplate = template("30", 7);
        keepingTemplate.setDiscardAfterGracePeriod(false);

        assertThat(
                "the template supplied the grace period, so it supplies the flag too",
                cloudWhereTemplateWins.resolveDiscardAfterGracePeriod(
                        computer(cloudWhereTemplateWins, keepingTemplate)),
                equalTo(false));
    }

    @Test
    void testIdleTerminationForLabelLooksUpByLabelName() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setIdleTimeoutMinutes(45);
        EC2Cloud cloud = cloud(rule);

        assertThat(cloud.getIdleTerminationForLabel(LABEL), equalTo(45));
        assertThat(cloud.getIdleTerminationForLabel("other"), nullValue());
    }

    /**
     * Renders and submits the cloud configuration page, which is the only way to catch a field of
     * the rule that the form does not bind, or a Jelly view that does not render at all.
     */
    @Test
    void testRuleSurvivesAConfigurationFormRoundtrip() throws Exception {
        HotSpareConfigByLabel rule = new HotSpareConfigByLabel(LABEL);
        rule.setBaseHotSpares(2);
        rule.setScalingFactor(3);
        rule.setMaxHotSpares(6);
        rule.setIdleTimeoutMinutes(20);
        rule.setGracePeriodMinutes(7);
        rule.setDiscardAfterGracePeriod(false);
        rule.setAllowTemplateIdleTimeoutOverride(true);
        rule.setAllowTemplateGracePeriodOverride(true);

        SlaveTemplate template = template("30", 7);
        template.setHotSpareWeight(3);

        EC2Cloud cloud = cloud(rule, template);
        cloud.setRoundRobinTemplatesByLabel(true);
        r.jenkins.clouds.add(cloud);

        r.submit(r.createWebClient().goTo(cloud.getUrl() + "configure").getFormByName("config"));

        EC2Cloud reloaded = r.jenkins.clouds.get(EC2Cloud.class);
        assertThat(reloaded.isRoundRobinTemplatesByLabel(), equalTo(true));
        assertThat(reloaded.getHotSpareConfigsByLabel().size(), equalTo(1));

        HotSpareConfigByLabel reloadedRule =
                reloaded.getHotSpareConfigsByLabel().get(0);
        assertThat(reloadedRule.getLabel(), equalTo(LABEL));
        assertThat(reloadedRule.getBaseHotSpares(), equalTo(2));
        assertThat(reloadedRule.getScalingFactor(), equalTo(3));
        assertThat(reloadedRule.getMaxHotSpares(), equalTo(6));
        assertThat(reloadedRule.getIdleTimeoutMinutes(), equalTo(20));
        assertThat(reloadedRule.getGracePeriodMinutes(), equalTo(7));
        assertThat(reloadedRule.isDiscardAfterGracePeriod(), equalTo(false));
        assertThat(reloadedRule.isAllowTemplateIdleTimeoutOverride(), equalTo(true));
        assertThat(reloadedRule.isAllowTemplateGracePeriodOverride(), equalTo(true));

        SlaveTemplate reloadedTemplate = reloaded.getTemplates().get(0);
        assertThat(reloadedTemplate.getHotSpareWeight(), equalTo(3));
        assertThat(reloadedTemplate.getGracePeriodMinutes(), equalTo(7));
    }

    /**
     * The rules are configured above the AMIs they draw on, and the label is a text field wired to
     * the completion endpoint rather than a drop-down.
     */
    @Test
    void testTheFormPutsTheRulesBeforeTheAmisAndTypesTheLabel() throws Exception {
        EC2Cloud cloud = cloud(new HotSpareConfigByLabel(LABEL), template("30", 0));
        r.jenkins.clouds.add(cloud);

        HtmlPage page = r.createWebClient().goTo(cloud.getUrl() + "configure");
        String html = page.getWebResponse().getContentAsString();

        assertThat(
                "the hot spare rules should come before the list of AMIs",
                html.indexOf("Hot spares by label"),
                lessThan(html.indexOf("List of AMIs to be launched as agents")));
        assertThat(html, containsString("autoCompleteLabel"));
        assertThat(
                "the label should be typed, so it is an input rather than a select",
                page.getFormByName("config").getInputByName("_.label").getAttribute("type"),
                equalTo("text"));
    }

    /**
     * The field is free text, so it carries an expression that no drop-down could have offered.
     */
    @Test
    void testALabelExpressionSurvivesAConfigurationFormRoundtrip() throws Exception {
        String expression = LABEL + " && gpu";
        EC2Cloud cloud = cloud(new HotSpareConfigByLabel(expression), template("30", 0));
        r.jenkins.clouds.add(cloud);

        r.submit(r.createWebClient().goTo(cloud.getUrl() + "configure").getFormByName("config"));

        assertThat(
                r.jenkins
                        .clouds
                        .get(EC2Cloud.class)
                        .getHotSpareConfigsByLabel()
                        .get(0)
                        .getLabel(),
                equalTo(expression));
    }

    /**
     * The label is typed rather than picked, so the field completes it from the labels the
     * configured templates carry, including part way through an expression.
     */
    @Test
    void testLabelCompletesFromTheConfiguredTemplates() {
        r.jenkins.clouds.add(cloud(null, template("30", 0), templateWithLabels("30", 0, "windows")));
        HotSpareConfigByLabel.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(HotSpareConfigByLabel.DescriptorImpl.class);

        assertThat(descriptor.doAutoCompleteLabel("").getValues(), hasItems(LABEL, "windows"));
        assertThat(descriptor.doAutoCompleteLabel("wind").getValues(), equalTo(List.of("windows")));
        assertThat(
                "the term after an operator is the one being completed",
                descriptor.doAutoCompleteLabel(LABEL + " && wind").getValues(),
                equalTo(List.of("windows")));
        assertThat(descriptor.doAutoCompleteLabel("nosuchlabel").getValues(), empty());
    }

    /**
     * A label expression is a valid value, but something that cannot be parsed as one is not:
     * Jenkins would fall back to treating it as a single label atom, which no template can carry,
     * and the rule would silently govern nothing.
     */
    @Test
    void testLabelExpressionsAreAcceptedAndUnparseableValuesAreNot() {
        HotSpareConfigByLabel.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(HotSpareConfigByLabel.DescriptorImpl.class);

        assertThat(descriptor.doCheckLabel(LABEL).kind, equalTo(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckLabel(LABEL + " && gpu").kind, equalTo(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckLabel("!windows").kind, equalTo(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckLabel("  ").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckLabel(LABEL + " &&").kind, equalTo(FormValidation.Kind.ERROR));
    }

    /**
     * The form endpoints are reachable by anyone who can log in, so they answer only to someone who
     * may configure the cloud. Validation goes quiet rather than failing, which is what keeps a
     * read-only view of the configuration usable, and completion offers nothing.
     */
    @Test
    void testTheFormEndpointsTellNonAdministratorsNothing() {
        r.jenkins.clouds.add(cloud(null, template("30", 0)));
        HotSpareConfigByLabel.DescriptorImpl descriptor =
                r.jenkins.getDescriptorByType(HotSpareConfigByLabel.DescriptorImpl.class);
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("reader"));

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertThat(descriptor.doCheckLabel(LABEL + " &&").kind, equalTo(FormValidation.Kind.OK));
            assertThat(descriptor.doCheckScalingFactor("-1").kind, equalTo(FormValidation.Kind.OK));
            assertThat(descriptor.doCheckBaseHotSpares("-1").kind, equalTo(FormValidation.Kind.OK));
            assertThat(descriptor.doCheckMaxHotSpares("1", "5").kind, equalTo(FormValidation.Kind.OK));
            assertThat(descriptor.doCheckGracePeriodMinutes("-1").kind, equalTo(FormValidation.Kind.OK));
            assertThat(descriptor.doCheckIdleTimeoutMinutes("not a number").kind, equalTo(FormValidation.Kind.OK));
            assertThrows(AccessDeniedException.class, () -> descriptor.doAutoCompleteLabel(""));
        }

        // The same values an administrator is told about, so the assertions above mean something.
        assertThat(descriptor.doCheckLabel(LABEL + " &&").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckScalingFactor("-1").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckBaseHotSpares("-1").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckMaxHotSpares("1", "5").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckGracePeriodMinutes("-1").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckIdleTimeoutMinutes("not a number").kind, equalTo(FormValidation.Kind.ERROR));
        assertThat(descriptor.doAutoCompleteLabel("").getValues(), hasItems(LABEL));
    }

    @Test
    void testHotSpareConfigsDefaultToEmptyRatherThanNull() throws Exception {
        EC2Cloud cloud = cloud(null);
        assertThat(cloud.getHotSpareConfigsByLabel().size(), equalTo(0));

        cloud.setHotSpareConfigsByLabel(null);
        assertThat(cloud.getHotSpareConfigsByLabel().size(), equalTo(0));
    }

    private MockEC2Computer computer(EC2Cloud cloud, SlaveTemplate template) throws Exception {
        MockEC2Computer computer = MockEC2Computer.createComputer("-precedence");
        computer.setSlaveTemplate(template);
        return computer;
    }

    private EC2Cloud cloud(HotSpareConfigByLabel rule, SlaveTemplate... templates) {
        EC2Cloud cloud =
                new EC2Cloud("test-cloud", true, "abc", "us-east-1", null, "ghi", "20", List.of(templates), null, null);
        if (rule != null) {
            cloud.setHotSpareConfigsByLabel(List.of(rule));
        }
        return cloud;
    }

    private static SlaveTemplate template(String idleTerminationMinutes, int gracePeriodMinutes) {
        return templateWithLabels(idleTerminationMinutes, gracePeriodMinutes, LABEL);
    }

    private static SlaveTemplate templateWithLabels(
            String idleTerminationMinutes, int gracePeriodMinutes, String labels) {
        SlaveTemplate template = new SlaveTemplate(
                "ami-123",
                EC2AbstractSlave.TEST_ZONE,
                null,
                "default",
                "/tmp/jenkins",
                InstanceType.M1_LARGE.toString(),
                false,
                labels,
                Node.Mode.NORMAL,
                "AMI description",
                "",
                "",
                "",
                "1",
                "",
                null,
                EC2AbstractSlave.DEFAULT_JAVA_PATH,
                "",
                false,
                "subnet-123",
                null,
                idleTerminationMinutes,
                0,
                0,
                null,
                "",
                false,
                false,
                "",
                false,
                "",
                false,
                false,
                false,
                ConnectionStrategy.PRIVATE_DNS,
                -1,
                Collections.emptyList(),
                null,
                Tenancy.Default,
                EbsEncryptRootVolume.DEFAULT,
                EC2AbstractSlave.DEFAULT_METADATA_ENDPOINT_ENABLED,
                EC2AbstractSlave.DEFAULT_METADATA_TOKENS_REQUIRED,
                EC2AbstractSlave.DEFAULT_METADATA_HOPS_LIMIT,
                EC2AbstractSlave.DEFAULT_METADATA_SUPPORTED,
                EC2AbstractSlave.DEFAULT_ENCLAVE_ENABLED);
        template.setGracePeriodMinutes(gracePeriodMinutes);
        return template;
    }
}
