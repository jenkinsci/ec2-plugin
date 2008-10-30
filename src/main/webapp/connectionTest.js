/**
 * Traverses a form in the reverse document order starting from the given element (but excluding it),
 * until the given filter matches, or run out of an element.
 */
function findPrevious(src,filter) {
    function prev(e) {
        var p = e.previousSibling;
        if(p==null) return e.parentNode;
        while(p.lastChild!=null)
            p = p.lastChild;
        return p;
    }

    while(src!=null) {
        src = prev(src);
        if(filter(src))
            return src;
    }
    return null;
}
function findPreviousFormItem(src,name) {
    var name2 = "_."+name; // handles <textbox field="..." /> notation silently
    return findPrevious(src,function(e){ return e.tagName=="INPUT" && (e.name==name || e.name==name2); });
}

function testEC2(checkUrl,paramList,button) {
  button = button._button;

  var parameters = {};

  paramList.split(',').each(function(name) {
      var p = findPreviousFormItem(button,name);
      if(p!=null)
        parameters[name] = p.value;
  });

  var spinner = Element.up(button,"DIV").nextSibling;
  var target = spinner.nextSibling;
  spinner.style.display="block";

  new Ajax.Request(checkUrl, {
      method: "post",
      parameters: parameters,
      onComplete: function(rsp) {
          spinner.style.display="none";
          target.innerHTML = rsp.responseText;
      }
  });
}
