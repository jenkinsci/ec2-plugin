function testEC2(rootURL,button) {
  button = button._button;
  var p = button;
  p = Element.up(p,"TR");
  p = Element.previous(p,null,2);
  pwd = Element.down(p,"INPUT");
  p = Element.previous(p,null,2);
  uid = Element.down(p,"INPUT");

  new Ajax.Request(rootURL+"/descriptor/hudson.plugins.ec2.EC2Cloud/testConnection", {
      method: "post",
      parameters: { uid:uid.value, pwd:pwd.value },
      onComplete: function(rsp) {
          var target = Element.up(button,"DIV").nextSibling;
          target.innerHTML = rsp.responseText;
      }
  });
}
