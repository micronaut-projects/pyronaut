(function () {
  var filter = 'all';
  var query = '';
  var tests = Array.prototype.slice.call(document.querySelectorAll('.test'));
  var groups = Array.prototype.slice.call(document.querySelectorAll('.group'));
  var empty = document.getElementById('empty');

  function apply() {
    var visibleTotal = 0;
    tests.forEach(function (t) {
      var matchesFilter = filter === 'all' || t.dataset.status === filter;
      var matchesQuery = !query || t.dataset.search.indexOf(query) !== -1;
      var visible = matchesFilter && matchesQuery;
      t.classList.toggle('hidden', !visible);
      if (visible) {
        visibleTotal++;
      }
    });
    groups.forEach(function (g) {
      // a group without rows, such as the blockers of the static compilation report, is not filtered
      if (g.querySelector('.test')) {
        g.classList.toggle('hidden', !g.querySelector('.test:not(.hidden)'));
      }
    });
    if (empty) {
      empty.classList.toggle('hidden', visibleTotal !== 0);
    }
    document.querySelectorAll('[data-filter]').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.filter === filter));
    });
  }

  document.querySelectorAll('[data-filter]').forEach(function (b) {
    b.addEventListener('click', function () {
      filter = b.dataset.filter === filter && filter !== 'all' ? 'all' : b.dataset.filter;
      apply();
    });
  });

  var search = document.getElementById('search');
  if (search) {
    search.addEventListener('input', function () {
      query = search.value.trim().toLowerCase();
      apply();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === '/' && document.activeElement !== search) {
        e.preventDefault();
        search.focus();
      }
    });
  }

  var toggle = document.getElementById('toggle-all');
  if (toggle) {
    toggle.addEventListener('click', function () {
      var open = toggle.dataset.open !== 'true';
      tests.forEach(function (t) {
        if (!t.classList.contains('hidden')) {
          t.open = open;
        }
      });
      toggle.dataset.open = String(open);
      toggle.textContent = open ? 'Collapse all' : 'Expand all';
    });
  }

  document.querySelectorAll('.copy').forEach(function (b) {
    b.addEventListener('click', function () {
      var pre = b.closest('.section').querySelector('pre');
      if (!pre || !navigator.clipboard) {
        return;
      }
      navigator.clipboard.writeText(pre.textContent).then(function () {
        b.textContent = 'Copied';
        setTimeout(function () { b.textContent = 'Copy'; }, 1400);
      });
    });
  });

  apply();
})();
