import { EditorView, basicSetup, yaml, oneDark, Compartment } from '/codemirror-bundle.js';

const themeCompartment = new Compartment();

function isDark() {
    return (document.documentElement.getAttribute('data-theme') || '').startsWith('dark');
}

const container = document.getElementById('editYamlEditor');
const view = new EditorView({
    extensions: [
        basicSetup,
        yaml(),
        themeCompartment.of(isDark() ? oneDark : []),
        EditorView.theme({
            '&': { height: '100%' },
            '.cm-scroller': { overflow: 'auto', fontFamily: "'Cascadia Code', 'Fira Code', monospace", fontSize: '13px' },
        }),
    ],
    parent: container,
});

new MutationObserver(function () {
    view.dispatch({ effects: themeCompartment.reconfigure(isDark() ? oneDark : []) });
}).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

window.yamlEditorAPI = {
    set: function (content) {
        view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: content } });
        // Force layout recalculation after modal becomes visible
        requestAnimationFrame(function () { view.requestMeasure(); });
    },
    get: function () {
        return view.state.doc.toString();
    },
};
