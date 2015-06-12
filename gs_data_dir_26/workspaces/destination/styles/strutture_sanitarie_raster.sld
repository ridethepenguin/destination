<?xml version="1.0" encoding="UTF-8"?>
<StyledLayerDescriptor version="1.0.0" 
 xsi:schemaLocation="http://www.opengis.net/sld StyledLayerDescriptor.xsd" 
 xmlns="http://www.opengis.net/sld" 
 xmlns:ogc="http://www.opengis.net/ogc" 
 xmlns:xlink="http://www.w3.org/1999/xlink" 
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <!-- a Named Layer is the basic building block of an SLD document -->
  <NamedLayer>
    <Name>strutture_sanitarie_raster</Name>
    <UserStyle>
    <!-- Styles can have names, titles and abstracts -->
      <Title>strutture_sanitarie_raster</Title>
      <FeatureTypeStyle>
        <Rule>
          <Title>Addetti e utenti strutture sanitarie
            <Localized lang="it">Addetti e utenti strutture sanitarie</Localized>
            <Localized lang="en">Sanitary Structrures Employees and Users</Localized>
            <Localized lang="fr">Structrures sanitaires employés et utilisateurs</Localized>
            <Localized lang="de">Sanitäre Strukturen</Localized>
          </Title>        
          <Name>rule1</Name>
          <Title>Opaque Raster</Title>
          <Abstract>A raster with 100% opacity</Abstract>
          <MinScaleDenominator>50000</MinScaleDenominator>
          <RasterSymbolizer>
              <Opacity>1.0</Opacity> 
              <ChannelSelection>
                <GrayChannel>
                        <SourceChannelName>1</SourceChannelName>
                </GrayChannel>
              </ChannelSelection>      
              <ColorMap type="intervals">
                <ColorMapEntry color="#000000" quantity="0.000000000001" label="" opacity="0"/>                                          
                <ColorMapEntry color="#C74C68" quantity="10000.0" label="" opacity="1"/>                             
              </ColorMap>      
              
          </RasterSymbolizer>
        </Rule>
      </FeatureTypeStyle>
    </UserStyle>
  </NamedLayer>
</StyledLayerDescriptor>