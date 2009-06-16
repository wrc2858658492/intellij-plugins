package com.intellij.tapestry.intellij.lang.descriptor;

import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.tapestry.core.model.presentation.Component;
import com.intellij.tapestry.core.model.presentation.TapestryParameter;
import com.intellij.tapestry.intellij.util.TapestryUtils;
import com.intellij.xml.XmlAttributeDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * @author Alexey Chmutov
 *         Date: Jun 15, 2009
 *         Time: 12:54:29 PM
 */
class DescriptorUtil {
  public static XmlAttributeDescriptor[] getAttributeDescriptors(@NotNull XmlTag context) {
    Component component = TapestryUtils.getComponentFromTag(context);
    if(component == null) return XmlAttributeDescriptor.EMPTY;
    Collection<TapestryParameter> params = component.getParameters().values();
    XmlAttributeDescriptor[] descriptors = new XmlAttributeDescriptor[params.size()];
    int i = 0;
    for(TapestryParameter param : params) {
      descriptors[i++] = new TapestryAttributeDescriptor(param);
    }
    return descriptors;
  }

  public static XmlAttributeDescriptor getAttributeDescriptor(@NotNull String attributeName, @NotNull XmlTag context) {
    Component component = TapestryUtils.getComponentFromTag(context);
    if(component == null) return null;
    XmlAttribute attr = TapestryUtils.getIdentifyingAttribute(context);
    if(attr != null && attr.getLocalName().equals(attributeName)) return new TapestryIdOrTypeAttributeDescriptor(attributeName); 
    TapestryParameter param = component.getParameters().get(attributeName);
    return param == null ? null : new TapestryAttributeDescriptor(param);
  }
}
