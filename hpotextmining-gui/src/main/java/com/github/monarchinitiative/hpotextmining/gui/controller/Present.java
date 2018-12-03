package com.github.monarchinitiative.hpotextmining.gui.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.github.monarchinitiative.hpotextmining.core.miners.biolark.BiolarkResult;
import com.github.monarchinitiative.hpotextmining.core.miners.scigraph.SciGraphResult;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.monarchinitiative.phenol.base.PhenolException;
import org.monarchinitiative.phenol.ontology.data.Term;
import org.monarchinitiative.phenol.ontology.data.TermId;
import org.monarchinitiative.phenol.ontology.data.TermPrefix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * This class is responsible for displaying the terms of performed text-mining analysis. <p>The controller accepts
 * response from the server performing text-mining analysis in JSON format and the analyzed text. The analyzed text
 * with highlighted term-containing regions is presented to the user. Tooltips containing the HPO term id and name are
 * also created for the highlighted regions. After clicking on the highlighted region, corresponding term is selected
 * in the ontology TreeView (left part of the main window).
 * <p>
 * Identified <em>YES</em> and <em>NOT</em> HPO terms are displayed on the right side of the screen as a set of
 * checkboxes. The user/biocurator is supposed to review the analyzed text and select those checkboxes that have been
 * identified correctly.
 * <p>
 * Selected terms must be approved with <em>Add selected terms</em> button in order to add them into the model.
 *
 * @author <a href="mailto:daniel.danis@jax.org">Daniel Danis</a>
 * @author <a href="mailto:aaron.zhangl@jax.org">Aaron Zhang</a>
 * @version 0.1.0
 * @since 0.1
 */
public class Present {

    private static final Logger LOGGER = LoggerFactory.getLogger(Present.class);

    /**
     * Header of html defining CSS & JavaScript for the presented text. CSS defines style for tooltips and
     * highlighted text. JavaScript code will allow focus on HPO term in the ontology treeview after clicking on the
     * highlighted text.
     */
    private static final String HTML_HEAD = "<html><head>" +
            "<style> .tooltip { position: relative; display: inline-block; border-bottom: 1px dotted black; }" +
            ".tooltip .tooltiptext { visibility: hidden; width: 230px; background-color: #555; color: #fff; " +
            "text-align: left;" +
            " border-radius: 6px; padding: 5px 0; position: absolute; z-index: 1; bottom: 125%; left: 50%; margin-left: -60px;" +
            " opacity: 0; transition: opacity 1s; }" +
            ".tooltip .tooltiptext::after { content: \"\"; position: absolute; top: 100%; left: 50%; margin-left: -5px;" +
            " border-width: 5px; border-style: solid; border-color: #555 transparent transparent transparent; }" +
            ".tooltip:hover .tooltiptext { visibility: visible; opacity: 1;}" +
            "</style>" +
            "<script>function focusOnTermJS(obj) {javafx_bridge.focusToTerm(obj);}</script>" +
            "</head>";

    private static final String HTML_BODY_BEGIN = "<body><h2>HPO text-mining analysis terms:</h2><p>";

    private static final String HTML_BODY_END = "</p></body></html>";

    /**
     * Html template for highlighting the text based on which a HPO term has been identified. Contains three
     * placeholders: <ol>
     * <li>HPO term ID (param for javascript, it will be used to focus on HPO term in the ontology tree)</li>
     * <li>part of the query text based on which the HPO term has been identified</li>
     * <li>tooltip text</li> </ol>
     * The initial space is intentional, it prevents lack of space between words with series of hits.
     */
    private static final String HIGHLIGHTED_TEMPLATE = " " +
            "<span class=\"tooltip\" style=\"color:red;\" onclick=\"focusOnTermJS('%s')\">%s" +
            "<span class=\"tooltiptext\">%s</span></span>";

    /**
     * Template for tooltips which appear when cursor hovers over highlighted terms.
     */
    private static final String TOOLTIP_TEMPLATE = "%s\n%s";

    private static final TermPrefix HP_TERM_PREFIX = new TermPrefix("HP");

    private final Consumer<TermId> focusToTermHook;

    private final Consumer<Main.Signal> signal;

    /**
     * The GUI element responsible for presentation of analyzed text with highlighted regions.
     */
    @FXML
    private WebView webView;

    private WebEngine webEngine;

    @FXML
    private Button addTermsButton;

    @FXML
    private Button cancelButton;

    /**
     * Box on the right side of the screen where "YES" Terms will be added.
     */
    @FXML
    private VBox yesTermsVBox;

    /**
     * Box on the right side of the screen where "NOT" Terms will be added.
     */
    @FXML
    private VBox notTermsVBox;

    /**
     * Array of generated checkboxes corresponding to identified <em>YES</em> HPO terms.
     */
    private CheckBox[] yesTerms;

    /**
     * Array of generated checkboxes corresponding to identified <em>NOT</em> HPO terms.
     */
    private CheckBox[] notTerms;

    /**
     * Store the received
     */
//    private Set<Main.PhenotypeTerm> terms = new HashSet<>();


    /**
     * @param signal          {@link Consumer} of {@link Main.Signal}
     *                        that will notify the upstream controller about status of the analysis
     * @param focusToTermHook {@link Consumer} that will accept {@link TermId} in order to show appropriate {@link Term} in ontology
     *                        tree view
     */
    Present(Consumer<Main.Signal> signal, Consumer<TermId> focusToTermHook) {
        this.signal = signal;
        this.focusToTermHook = focusToTermHook;
    }

    /**
     * Create checkbox on the fly applying desired style, padding, etc. The <code>term</code> is added to {@link CheckBox}
     * so that it can be later retrieved using {@link CheckBox#getUserData()}.
     *
     * @param term - {@link Term} to be represented by a Checkbox
     * @return created {@link CheckBox} instance
     */
    private static CheckBox checkBoxFactory(Main.PhenotypeTerm term) {
        CheckBox cb = new CheckBox(term.getTerm().getName());
        cb.setPadding(new Insets(5));
        cb.setUserData(term);
        return cb;
    }

    /**
     * Parse JSON string from Tudor Server into set of intermediate result objects.
     *
     * @param jsonResponse JSON string to be parsed.
     * @return set of {@link BiolarkResult} objects.
     * @throws IOException in case of parsing problems
     */
    @Deprecated
    private static Set<BiolarkResult> decodePayload(String jsonResponse) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CollectionType javaType = mapper.getTypeFactory().constructCollectionType(Set.class, BiolarkResult.class);
        return mapper.readValue(jsonResponse, javaType);
    }

    /**
     * Parse JSON string from Monarch SciGraph Server into set of intermediate result objects.
     *
     * @param jsonResponse JSON string to be parsed.
     * @return set of {@link BiolarkResult} objects.
     * @throws IOException in case of parsing problems
     * @author Aaron Zhang
     */
    @Deprecated
    private static Set<BiolarkResult> decodePayload(String jsonResponse, String queryText) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        //map json result into SciGraphResult objects
        SciGraphResult[] sciGraphResults = mapper.readValue(jsonResponse, SciGraphResult[].class);
        //convert into BiolarkResults and return hpo terms
        return Arrays.stream(sciGraphResults).map(o -> SciGraphResult.toBiolarkResult(o, queryText))
                .filter(o -> o.getSource().startsWith("HP")).collect(Collectors.toSet());
    }


    /**
     * Similar to above but this one works for terms from SciGraph server
     *
     * @author Aaron Zhang
     */
    private String colorizeHTML4ciGraph(Collection<Main.PhenotypeTerm> terms, String query) {
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append(HTML_HEAD);
        htmlBuilder.append(HTML_BODY_BEGIN);

        // sort to process minedText sequentially.
        final List<Main.PhenotypeTerm> sortedByBegin = terms.stream()
                .sorted(Comparator.comparing(Main.PhenotypeTerm::getBegin))
                .collect(Collectors.toList());

        int offset = 0;
        for (Main.PhenotypeTerm term : sortedByBegin) {
            int start = Math.max(term.getBegin(), offset);
            htmlBuilder.append(query.substring(offset, start)); // unhighlighted text
            //start = Math.max(offset + 1, result.getStart());
            //Term id is an information such as "HP:0000822"
            htmlBuilder.append(
                    // highlighted text
                    String.format(HIGHLIGHTED_TEMPLATE,
                            term.getTerm().getId().toString(),
                            query.substring(start, term.getEnd()),

                            // tooltip text -> HPO id & label
                            String.format(TOOLTIP_TEMPLATE, term.getTerm().getId().getIdWithPrefix(), term.getTerm().getName())));

            offset = term.getEnd();
        }

        // process last part of mined text, if there is any
        htmlBuilder.append(query.substring(offset));
        htmlBuilder.append(HTML_BODY_END);
        // get rid of double spaces
        return htmlBuilder.toString().replaceAll("\\s{2,}", " ").trim();
    }


    /**
     * End of analysis. Add approved terms into {@link Main#hpoTermsTableView} and display configure
     * Dialog to allow next round of text-mining analysis.
     */
    @FXML
    void addTermsButtonAction() {
        signal.accept(Main.Signal.DONE);
    }


    /**
     * After hitting {@link Present#cancelButton} the analysis is ended and a new {@link Configure} dialog is presented
     * to the user.
     */
    @FXML
    void cancelButtonAction() {
        signal.accept(Main.Signal.CANCELLED);
    }


    /**
     * {@inheritDoc}
     */
    public void initialize() {
        webEngine = webView.getEngine();
        // register JavaBridge object in the JavaScript engine of the webEngine
        webEngine.getLoadWorker().stateProperty().addListener((ov, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) webEngine.executeScript("window");
                win.setMember("javafx_bridge", new JavaBridge());
                // redirect JavaScript console.LOGGER() to sysout defined in the JavaBridge
                webEngine.executeScript("console.log = function(message) {javafx_bridge.log(message);};");
            }
        });
    }


    /**
     * The data that are about to be presented are set here. The String with JSON terms are coming from the
     * text-mining analysis performing server while the mined text is the text submitted by the user in Configure Dialog
     * (controlled by {@link Configure}).
     *
     * @param terms String in JSON format containing the result of text-mining analysis.
     * @param query String with the query text submitted by the user.
     */
    void setResults(Collection<Main.PhenotypeTerm> terms, String query) {
        yesTermsVBox.getChildren().clear();
        notTermsVBox.getChildren().clear();

        Set<String> presentAdded = new HashSet<>();
        Set<String> notPresentAdded = new HashSet<>();

        List<Main.PhenotypeTerm> termList = new ArrayList<>(terms);
        termList.sort(Comparator.comparing(t -> t.getTerm().getName()));

        for (Main.PhenotypeTerm phenotypeTerm : termList) {
            if (phenotypeTerm.isPresent()) {
                if (!presentAdded.contains(phenotypeTerm.getTerm().getId().getIdWithPrefix())) {
                    presentAdded.add(phenotypeTerm.getTerm().getId().getIdWithPrefix());
                    yesTermsVBox.getChildren().add(checkBoxFactory(phenotypeTerm));
                }
            } else {
                if (!notPresentAdded.contains(phenotypeTerm.getTerm().getId().getIdWithPrefix())) {
                    notPresentAdded.add(phenotypeTerm.getTerm().getId().getIdWithPrefix());
                    notTermsVBox.getChildren().add(checkBoxFactory(phenotypeTerm));
                }
            }

        }

        String html = colorizeHTML4ciGraph(termList, query);
        webEngine.loadContent(html);
    }


    /**
     * Return the final set of <em>YES</em> & <em>NOT</em> {@link Main.PhenotypeTerm} objects which have been approved by
     * curator by ticking the checkbox.
     *
     * @return {@link Set} of approved {@link Main.PhenotypeTerm}s.
     */
    Set<Main.PhenotypeTerm> getApprovedTerms() {

        List<CheckBox> boxes = new ArrayList<>();
        for (Node child : yesTermsVBox.getChildren()) {
            CheckBox b = ((CheckBox) child);
            boxes.add(b);
        }

        for (Node child : notTermsVBox.getChildren()) {
            CheckBox b = ((CheckBox) child);
            boxes.add(b);
        }

        return boxes.stream()
                .filter(CheckBox::isSelected)
                .map(cb -> ((Main.PhenotypeTerm) cb.getUserData()))
                .collect(Collectors.toSet());
    }


    /**
     * This class is the bridge between JavaScript run in the {@link #webView} and Java code.
     */
    public class JavaBridge {

        public void log(String message) {
            LOGGER.info(message);
        }


        /**
         * @param termId String like HP:1234567
         */
        public void focusToTerm(String termId) {
            LOGGER.debug("Focusing on term with ID {}", termId);
            try {
                TermId id = TermId.constructWithPrefixInternal(termId);
                focusToTermHook.accept(id);
            } catch (PhenolException e) {
                LOGGER.warn("Unable to focus on term with id '{}'", termId, e);
            }
        }
    }
}