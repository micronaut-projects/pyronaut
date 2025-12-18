function toValidHtmlId(input) {
    // 1. Replace invalid characters (e.g. slashes, dots) with underscores
    let result = input.replace(/[^a-zA-Z0-9\-_:.]/g, '_');

    // 2. Ensure it starts with a letter (prepend 'id_' if not)
    if (!/^[a-zA-Z]/.test(result)) {
        result = 'id_' + result;
    }

    return result;
}

document.addEventListener("DOMContentLoaded", function () {
    document.body.addEventListener('htmx:afterSwap', function (event) {
        if (event.target.id === 'preview-modal') {
            Prism.highlightAll();
        }
    });
});

