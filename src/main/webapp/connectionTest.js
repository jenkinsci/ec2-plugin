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
    return findPrevious(src,function(e){ return e.tagName=="INPUT" && e.name==name; });
}

function testEC2(rootURL,button) {
  button = button._button;

  var pwd = findPreviousFormItem(button,"_.secretKey");
  var uid = findPreviousFormItem(pwd,"_.accessId");

  new Ajax.Request(rootURL+"/descriptor/hudson.plugins.ec2.EC2Cloud/testConnection", {
      method: "post",
      parameters: { uid:uid.value, pwd:pwd.value },
      onComplete: function(rsp) {
          var target = Element.up(button,"DIV").nextSibling;
          target.innerHTML = rsp.responseText;
      }
  });
}
